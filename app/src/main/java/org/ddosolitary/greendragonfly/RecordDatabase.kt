package org.ddosolitary.greendragonfly

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bugsnag.android.Bugsnag
import java.security.Key
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "records")
class RecordEntry(
	@PrimaryKey(autoGenerate = true) val id: Int,
	@ColumnInfo(name = "encryptedRecord") val encryptedRecord: ByteArray,
	@ColumnInfo(name = "iv") val iv: ByteArray,
	@ColumnInfo(name = "isUploaded") val isUploaded: Boolean
) {
	companion object {
		const val KEYSTORE_PROVIDER = "AndroidKeyStore"
		const val ENCRYPTION_KEY_ALIAS = "record-encryption-key"
		const val ENCRYPTION_CIPHER = "AES/GCM/NoPadding"
		const val ENCRYPTION_IV_LEN = 12
		const val ENCRYPTION_TAG_LEN = 128
		const val LOG_TAG = "RecordEntry"

		private fun genKey(): Key {
			val props = KeyGenParameterSpec.Builder(
				ENCRYPTION_KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setRandomizedEncryptionRequired(false)
				.build()
			return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
				.run {
					init(props)
					generateKey()
				}
		}

		private fun getKey(): Key {
			return KeyStore.getInstance(KEYSTORE_PROVIDER).run {
				load(null)
				getKey(ENCRYPTION_KEY_ALIAS, null) ?: genKey().also {
					setKeyEntry(ENCRYPTION_KEY_ALIAS, it, null, null)
				}
			}
		}

		fun encryptRecord(record: Record): RecordEntry {
			val iv = ByteArray(ENCRYPTION_IV_LEN)
			SecureRandom().nextBytes(iv)
			val param = GCMParameterSpec(ENCRYPTION_TAG_LEN, iv)
			return RecordEntry(
				record.id,
				Cipher.getInstance(ENCRYPTION_CIPHER).run {
					init(Cipher.ENCRYPT_MODE, getKey(), param)
					updateAAD(byteArrayOf(if (record.isUploaded) 1 else 0))
					doFinal(StampedLocation.listToJson(record.locations).encodeToByteArray())
				},
				iv,
				record.isUploaded,
			)
		}
	}

	fun decryptRecord(shouldVerify: Boolean = true): Record? {
		var result: List<StampedLocation>? = null
		try {
			val param = GCMParameterSpec(ENCRYPTION_TAG_LEN, iv)
			val data = Cipher.getInstance(ENCRYPTION_CIPHER).run {
				init(Cipher.DECRYPT_MODE, getKey(), param)
				if (shouldVerify) {
					updateAAD(byteArrayOf(if (isUploaded) 1 else 0))
				}
				doFinal(encryptedRecord)
			}
			result = StampedLocation.jsonToList(String(data))
		} catch (e: Exception) {
			Log.e(LOG_TAG, Log.getStackTraceString(e))
			Bugsnag.notify(e)
		}
		return if (result == null) null else Record(id, result, isUploaded)
	}
}

@Dao
interface RecordDao {
	@Query("SELECT * FROM records")
	fun getRecords(): List<RecordEntry>

	@Insert
	fun addRecord(record: RecordEntry)

	@Update
	fun updateRecord(record: RecordEntry)

	@Query("DELETE FROM records WHERE id = :id")
	fun deleteRecordById(id: Int)

	@Query("SELECT COUNT(id) FROM records")
	fun getRecordCount(): Int

	@Query("SELECT * FROM records ORDER BY id DESC LIMIT 1")
	fun getLastRecord(): RecordEntry

	@Query("SELECT * FROM records WHERE id = :id")
	fun getRecordById(id: Int): RecordEntry
}

@Database(entities = [RecordEntry::class], version = 2)
abstract class RecordDatabase : RoomDatabase() {
	companion object {
		val MIGRATION_1_2 = object : Migration(1, 2) {
			override fun migrate(database: SupportSQLiteDatabase) {
				database.query("SELECT * FROM records").use {
					val idColumn = it.getColumnIndex("id")
					val encryptedRecordColumn = it.getColumnIndex("encryptedRecord")
					val ivColumn = it.getColumnIndex("iv")
					val isUploadedColumn = it.getColumnIndex("isUploaded")
					while (it.moveToNext()) {
						val record = RecordEntry(
							it.getInt(idColumn),
							it.getBlob(encryptedRecordColumn),
							it.getBlob(ivColumn),
							it.getInt(isUploadedColumn) != 0,
						).decryptRecord(false)
						if (record != null) {
							val recordEntry = RecordEntry.encryptRecord(record)
							database.update(
								"records",
								SQLiteDatabase.CONFLICT_NONE,
								ContentValues().apply {
									put("encryptedRecord", recordEntry.encryptedRecord)
									put("iv", recordEntry.iv)
								},
								"id = ?",
								arrayOf(recordEntry.id),
							)
						}
					}
				}
			}
		}
	}

	abstract fun recordDao(): RecordDao
}

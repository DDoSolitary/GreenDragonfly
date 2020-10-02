package org.ddosolitary.greendragonfly

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.*
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
					doFinal(StampedLocation.listToJson(record.locations).encodeToByteArray())
				},
				iv,
				record.isUploaded,
			)
		}
	}

	fun decryptRecord(): Record? {
		var result: List<StampedLocation>? = null
		try {
			val param = GCMParameterSpec(ENCRYPTION_TAG_LEN, iv)
			val data = Cipher.getInstance(ENCRYPTION_CIPHER).run {
				init(Cipher.DECRYPT_MODE, getKey(), param)
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

@Database(entities = [RecordEntry::class], exportSchema = false, version = 1)
abstract class RecordDatabase : RoomDatabase() {
	abstract fun recordDao(): RecordDao
}

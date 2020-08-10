package org.ddosolitary.greendragonfly

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.serialization.toUtf8Bytes
import java.security.Key
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val ENCRYPTION_KEY_ALIAS = "record-encryption-key"
private const val ENCRYPTION_CIPHER = "AES/GCM/NoPadding"
private const val ENCRYPTION_IV_LEN = 12
private const val ENCRYPTION_TAG_LEN = 128
private const val LOG_TAG = "RecordEntry"

@Entity(tableName = "records")
class RecordEntry(
	@ColumnInfo(name = "encryptedRecord") val encryptedRecord: ByteArray,
	@ColumnInfo(name = "iv") val iv: ByteArray,
	@ColumnInfo(name = "isUploaded") var isUploaded: Boolean
) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
	@Ignore
	val locations: List<StampedLocation>?

	init {
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
			FirebaseCrashlytics.getInstance().recordException(e)
		} finally {
			locations = result
		}
	}

	companion object {
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

		fun fromLocations(locations: List<StampedLocation>): RecordEntry {
			val iv = ByteArray(ENCRYPTION_IV_LEN)
			SecureRandom().nextBytes(iv)
			val param = GCMParameterSpec(ENCRYPTION_TAG_LEN, iv)
			return RecordEntry(Cipher.getInstance(ENCRYPTION_CIPHER).run {
				init(Cipher.ENCRYPT_MODE, getKey(), param)
				doFinal(StampedLocation.listToJson(locations).toUtf8Bytes())
			}, iv, false)
		}
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

	@Delete
	fun deleteRecord(record: RecordEntry)

	@Query("SELECT COUNT(id) FROM records")
	fun getRecordCount(): Int

	@Query("SELECT * FROM records ORDER BY id DESC LIMIT 1")
	fun getLastRecord(): RecordEntry
}

@Database(entities = [RecordEntry::class], exportSchema = false, version = 1)
abstract class RecordDatabase : RoomDatabase() {
	abstract fun recordDao(): RecordDao
}

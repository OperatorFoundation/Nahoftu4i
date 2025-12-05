package org.nahoft.nahoft

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.nahoft.codex.PersistenceEncryption
import org.simpleframework.xml.core.Persister
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Exception
import org.libsodium.jni.keys.PublicKey

class Persist
{
    companion object
    {
        const val sharedPrefImageSaveConsentShownKey = "NahoftImageSaveConsentShown"

        val publicKeyPreferencesKey = "NahoftPublicKey"
        val sharedPrefLoginStatusKey = "NahoftLoginStatus"
        val sharedPrefPasscodeKey = "NahoftPasscode"
        val sharedPrefSecondaryPasscodeKey = "NahoftSecondaryPasscode"
        val sharedPrefFailedLoginAttemptsKey = "NahoftFailedLogins"

        // Expiry-based lockout keys
        val sharedPrefLockoutExpiryKey = "NahoftLockoutExpiry"
        val sharedPrefLockoutElapsedKey = "NahoftLockoutElapsed"

//        val sharedPrefUseSmsAsDefaultKey = "NahoftUseSmsAsDefault"
        val sharedPrefAlreadySeeTutorialKey = "NahoftAlreadySeeTutorial"

        val sharedPrefFilename = "NahoftEncryptedPreferences"

        val sharedPrefKeyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
        val masterKeyAlias = MasterKeys.getOrCreate(sharedPrefKeyGenParameterSpec)

        const val friendsFilename = "fData.xml"
        const val messagesFilename = "mData.xml"

        // Initialized by EnterPasscodeActivity(main)
        lateinit var status: LoginStatus
        lateinit var encryptedSharedPreferences: EncryptedSharedPreferences

        // Initialized by HomeActivity
        lateinit var friendsFile: File
        lateinit var messagesFile: File
        lateinit var app: Application
//        var sendWithSms by Delegates.notNull<Boolean>()

        var friendList = ArrayList<Friend>()
        var messageList = ArrayList<Message>()

        fun getStatus()
        {
            val statusString = encryptedSharedPreferences.getString(Persist.sharedPrefLoginStatusKey, null)

            status = if (statusString != null)
            {
                try
                {
                    LoginStatus.valueOf(statusString)
                }
                catch (error: Exception)
                {
                    print("Received invalid status from EncryptedSharedPreferences. User is logged out.")
                    LoginStatus.LoggedOut
                }
            }
            else
            {
                LoginStatus.NotRequired
            }
        }

        fun saveLoginStatus() {
            encryptedSharedPreferences
                .edit()
                .putString(sharedPrefLoginStatusKey, status.name)
                .apply()
        }

        /**
         * Returns the lockout duration in milliseconds for a given number of failed attempts.
         *
         * Lockout schedule:
         * - 0-5 attempts: no lockout
         * - 6 attempts: 1 minute
         * - 7 attempts: 5 minutes
         * - 8 attempts: 15 minutes
         * - 9+ attempts: 1000 minutes (triggers data wipe)
         */
        fun getLockoutDurationMillis(failedLoginAttempts: Int): Long
        {
            val minutes = when {
                failedLoginAttempts >= 9 -> 1000
                failedLoginAttempts == 8 -> 15
                failedLoginAttempts == 7 -> 5
                failedLoginAttempts == 6 -> 1
                else -> 0
            }

            return minutes * 60 * 1000L
        }

        /**
         * Saves a login failure and sets up the lockout expiry.
         *
         * Stores:
         * - Failed attempt count
         * - Wall clock time when lockout expires
         * - SystemClock.elapsedRealtime() at lockout creation (for manipulation detection)
         *
         * When failedLoginAttempts is 0, clears all lockout-related keys.
         */
        fun saveLoginFailure(failedLoginAttempts: Int)
        {
            // Save number of failed login attempts
            encryptedSharedPreferences
                .edit()
                .putInt(sharedPrefFailedLoginAttemptsKey, failedLoginAttempts)
                .apply()

            if (failedLoginAttempts == 0)
            {
                // Clear lockout state on successful login
                encryptedSharedPreferences
                    .edit()
                    .remove(sharedPrefLockoutExpiryKey)
                    .remove(sharedPrefLockoutElapsedKey)
                    .apply()
            }
            else
            {
                val lockoutDuration = getLockoutDurationMillis(failedLoginAttempts)
                val lockoutExpiry = System.currentTimeMillis() + lockoutDuration
                val elapsedAtLockout = SystemClock.elapsedRealtime()

                encryptedSharedPreferences
                    .edit()
                    .putLong(sharedPrefLockoutExpiryKey, lockoutExpiry)
                    .putLong(sharedPrefLockoutElapsedKey, elapsedAtLockout)
                    .apply()
            }
        }

        /**
         * Checks if the current lockout has expired.
         *
         * Returns true if EITHER:
         * - Wall clock is past the expiry time, OR
         * - Enough real time (elapsedRealtime) has passed since lockout was set
         *
         * if the user travels and their wall clock moves backward,
         * they can still log in once enough real time has passed.
         *
         * Reboot detection: If elapsedRealtime() is less than the stored value,
         * the device has rebooted, fall back to wall clock only.
         */
        fun isLockoutExpired(failedLoginAttempts: Int): Boolean
        {
            val lockoutDuration = getLockoutDurationMillis(failedLoginAttempts)

            // No lockout for fewer than 6 attempts
            if (lockoutDuration == 0L) return true

            val lockoutExpiration = encryptedSharedPreferences.getLong(sharedPrefLockoutExpiryKey, 0L)
            val elapsedAtLockout = encryptedSharedPreferences.getLong(sharedPrefLockoutElapsedKey, 0L)

            // No lockout data stored
            if (lockoutExpiration == 0L) return true

            val now = System.currentTimeMillis()
            val currentElapsed = SystemClock.elapsedRealtime()

            // Check for reboot: elapsedRealtime resets to 0 on boot
            // If stored value is greater than current, device has rebooted
            val deviceRebooted = elapsedAtLockout > currentElapsed

            val wallClockExpired = now >= lockoutExpiration

            if (deviceRebooted)
            {
                // Can't verify with monotonic time, fall back to wall clock
                return wallClockExpired
            }

            val elapsedSinceLockout = currentElapsed - elapsedAtLockout
            val realTimeExpired = elapsedSinceLockout >= lockoutDuration

            // Lenient: allow if EITHER measure shows expired
            return wallClockExpired || realTimeExpired
        }

        /**
         * Returns true if the wall clock shows the lockout has expired,
         * but not enough real time has actually passed. This indicates
         * the user moved their clock forward to bypass the lockout.
         *
         * Returns false if:
         * - Device has rebooted (can't reliably detect manipulation)
         * - No active lockout
         * - No manipulation detected
         */
        fun isClockManipulationDetected(failedLoginAttempts: Int): Boolean
        {
            val lockoutDuration = getLockoutDurationMillis(failedLoginAttempts)

            // No lockout active
            if (lockoutDuration == 0L) return false

            val lockoutExpired = encryptedSharedPreferences.getLong(sharedPrefLockoutExpiryKey, 0L)
            val elapsedAtLockout = encryptedSharedPreferences.getLong(sharedPrefLockoutElapsedKey, 0L)

            // No lockout data stored
            if (lockoutExpired == 0L || elapsedAtLockout == 0L) return false

            val now = System.currentTimeMillis()
            val currentElapsed = SystemClock.elapsedRealtime()

            // Check for reboot — can't reliably detect manipulation after reboot
            if (elapsedAtLockout > currentElapsed) return false

            val wallClockExpired = now >= lockoutExpired
            val elapsedSinceLockout = currentElapsed - elapsedAtLockout
            val realTimeExpired = elapsedSinceLockout >= lockoutDuration

            // Manipulation: wall clock says expired, but real time disagrees
            return wallClockExpired && !realTimeExpired
        }

        /**
         * Returns the remaining lockout time in milliseconds.
         * Uses the more accurate of wall clock or elapsed time remaining.
         * Returns 0 if lockout has expired.
         */
        fun getRemainingLockoutMillis(failedLoginAttempts: Int): Long
        {
            val lockoutDuration = getLockoutDurationMillis(failedLoginAttempts)

            if (lockoutDuration == 0L) return 0L

            val lockoutExpired = encryptedSharedPreferences.getLong(sharedPrefLockoutExpiryKey, 0L)
            val elapsedAtLockout = encryptedSharedPreferences.getLong(sharedPrefLockoutElapsedKey, 0L)

            if (lockoutExpired == 0L) return 0L

            val now = System.currentTimeMillis()
            val currentElapsed = SystemClock.elapsedRealtime()

            // Wall clock remaining
            val wallClockRemaining = maxOf(0L, lockoutExpired - now)

            // Check for reboot
            if (elapsedAtLockout > currentElapsed) return wallClockRemaining

            // Real time remaining
            val elapsedSinceLockout = currentElapsed - elapsedAtLockout
            val realTimeRemaining = maxOf(0L, lockoutDuration - elapsedSinceLockout)

            // Return the smaller value
            return minOf(wallClockRemaining, realTimeRemaining)
        }

        fun accessIsAllowed(): Boolean
        {
            getStatus()

            // Return true if status is NotRequired or LoggedIn
            return status == LoginStatus.NotRequired || status == LoginStatus.LoggedIn
        }

        fun updateFriend(context: Context, friendToUpdate: Friend, newName: String = friendToUpdate.name, newStatus: FriendStatus = friendToUpdate.status, encodedPublicKey: ByteArray? = null) {

            val oldFriend = friendList.find { it.name == friendToUpdate.name }

            encodedPublicKey?.let {
                val publicKey = PublicKey(encodedPublicKey)
                if (publicKey == null) {
                    // Fail early instead of persisting a bad public key
                    return
                }
            }

            oldFriend?.let {
                oldFriend.status = newStatus
                oldFriend.name = newName

                encodedPublicKey?.let { oldFriend.publicKeyEncoded = encodedPublicKey }
            }

            saveFriendsToFile(context)
        }

        fun updateFriendsPhone(context: Context, friendToUpdate: Friend, newPhoneNumber: String) {

            val oldFriend = friendList.find { it.name == friendToUpdate.name }

//            oldFriend?.let {
//                oldFriend.phone = newPhoneNumber
//            }

            saveFriendsToFile(context)
        }

        // Save something to Encrypted Shared Preferences
        fun saveKey(key:String, value:String) {
            encryptedSharedPreferences
                .edit()
                .putString(key, value)
                .apply()
        }

        fun saveBooleanKey(key:String, value:Boolean) {
            encryptedSharedPreferences
                .edit()
                .putBoolean(key, value)
                .apply()
        }

        fun loadBooleanKey(key: String): Boolean {
            return encryptedSharedPreferences.getBoolean(key, false)
        }

        // Remove something from Encrypted Shared Preferences
        private fun deleteKey(key:String) {
            encryptedSharedPreferences
                .edit()
                .remove(key)
                .apply()
        }

        fun deletePasscode() {
            deleteKey(sharedPrefPasscodeKey)
            deleteKey(sharedPrefSecondaryPasscodeKey)
        }

        fun loadEncryptedSharedPreferences(context: Context) {
            encryptedSharedPreferences = EncryptedSharedPreferences.create(
                sharedPrefFilename,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        }

        fun deleteMessage(context: Context, message: Message) {
            messageList.remove(message)
            saveMessagesToFile(context)
        }

        fun clearAllData(secondaryCode: Boolean) {
            if (friendsFile.exists()) { friendsFile.delete() }
            if (messagesFile.exists()) { messagesFile.delete() }
            friendList.clear()
            messageList.clear()

            var passcode = ""
            if (secondaryCode) {
                passcode = encryptedSharedPreferences.getString(sharedPrefSecondaryPasscodeKey, "").toString()
            }
            // Overwrite the keys to EncryptedSharedPreferences
            val keyHex = "0000000000000000000000000000000000000000000000000000000000000000"

            encryptedSharedPreferences
                .edit()
                .putString("NahoftPrivateKey", keyHex)
                .putString(publicKeyPreferencesKey, keyHex)
                .apply()

            // Remove Everything from EncryptedSharedPreferences
            encryptedSharedPreferences
                .edit()
                .clear()
                .apply()

            if (secondaryCode) {
                saveKey(sharedPrefPasscodeKey, passcode)
                saveBooleanKey(sharedPrefAlreadySeeTutorialKey, true)
                status = LoginStatus.LoggedIn
                saveLoginStatus()
            } else {
                status = LoginStatus.NotRequired
            }
        }

        fun saveFriendsToFile(context: Context) {
            val serializer = Persister()
            val outputStream = ByteArrayOutputStream()
            val friendsObject = Friends(friendList)

            try { serializer.write(friendsObject, outputStream) }
            catch (error: Exception) {
                print("Failed to serialize our friends list: $error")
            }

            PersistenceEncryption().writeEncryptedFile(friendsFile, outputStream.toByteArray(), context)
        }

        fun saveMessagesToFile(context: Context) {
            val serializer = Persister()
            val outputStream = ByteArrayOutputStream()
            val messagesObject = Messages(messageList)
            try { serializer.write(messagesObject, outputStream) }
            catch (error: Exception) {
                print("Failed to serialize our messages list: $error")
                return
            }

            PersistenceEncryption().writeEncryptedFile(messagesFile, outputStream.toByteArray(), context)
        }

        fun resetFriend(context: Context, friend: Friend)
        {
            messageList.removeIf { it.sender == friend }
            friend.publicKeyEncoded = null
            updateFriend(context, friend, newStatus = FriendStatus.Default)

            saveMessagesToFile(context)
            saveFriendsToFile(context)
        }

        fun removeFriendAt(context: Context, friend: Friend)
        {
            val byeFriend = friendList.find { it.name == friend.name }
            messageList.removeIf { it.sender == byeFriend }
            byeFriend?.publicKeyEncoded = null
            friendList.remove(byeFriend)

            saveMessagesToFile(context)
            saveFriendsToFile(context)
        }
    }

}
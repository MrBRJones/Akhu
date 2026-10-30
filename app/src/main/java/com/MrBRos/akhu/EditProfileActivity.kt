package com.MrBRos.akhu

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.IOException

/**
 * Edit profile, like Instagram's: the picture with "Edit picture" under it, then Name, Username,
 * Pronouns and Bio. Save checks the username is free before keeping anything.
 */
class EditProfileActivity : AppCompatActivity() {

    private var name by mutableStateOf("")
    private var username by mutableStateOf("")
    private var pronouns by mutableStateOf("")
    private var bio by mutableStateOf("")
    private var usernameError by mutableStateOf<Int?>(null)
    private var saving by mutableStateOf(false)
    private var avatarVersion by mutableLongStateOf(0L)

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.data?.let { pickAvatar(this, it) { avatarVersion = System.currentTimeMillis() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Social.local(this)
        name = p.name.ifEmpty { FirebaseHelper.currentUser(this)?.let(FirebaseHelper::displayNameOf) ?: "" }
        username = p.username
        pronouns = p.pronouns
        bio = p.bio
        setContent {
            AkhuTheme {
                EditProfileScreen(
                    avatar = Social.avatarFile(this) ?: Social.local(this).photoUrl,
                    avatarVersion = avatarVersion,
                    name = name, onName = { name = it.take(50) },
                    username = username, onUsername = { username = it.take(30); usernameError = null },
                    usernameError = usernameError,
                    pronouns = pronouns, onPronouns = { pronouns = it.take(30) },
                    bio = bio, onBio = { bio = it.take(150) },
                    saving = saving,
                    onBack = ::finish,
                    onPicture = { pickLauncher.launch(ImagePickerHelper.createPickIntent()) },
                    onSave = ::save,
                )
            }
        }
    }

    private fun save() {
        val user = Social.normalizeUsername(username)
        if (!Social.isValidUsername(user)) {
            usernameError = R.string.username_invalid
            return
        }
        saving = true
        val profile = Profile(name.trim(), user, pronouns.trim(), bio.trim())
        Social.save(this, profile).addOnCompleteListener(this) { t ->
            saving = false
            when {
                t.isSuccessful -> {
                    Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                t.exception is Social.UsernameTaken -> usernameError = R.string.username_taken
                else -> Toast.makeText(this, getString(R.string.save_failed) + ": " + t.exception?.message,
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** Makes a picked picture the profile picture, then calls [done] on the main thread. */
        fun pickAvatar(activity: Activity, uri: Uri, done: () -> Unit) {
            val app = activity.applicationContext
            Thread({
                try {
                    val file = ImagePickerHelper.copyToCache(app, uri)
                    val upload = Social.setAvatar(app, file)
                    activity.runOnUiThread(done)
                    upload?.addOnCompleteListener { t ->
                        if (t.isSuccessful) activity.runOnUiThread(done)
                        else Toast.makeText(app, app.getString(R.string.upload_failed) + ": " + t.exception?.message,
                            Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    activity.runOnUiThread {
                        Toast.makeText(app, e.message ?: app.getString(R.string.picture_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }, "Avatar").start()
        }
    }
}

@Composable
private fun EditProfileScreen(
    avatar: Any?,
    avatarVersion: Long,
    name: String, onName: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    usernameError: Int?,
    pronouns: String, onPronouns: (String) -> Unit,
    bio: String, onBio: (String) -> Unit,
    saving: Boolean,
    onBack: () -> Unit,
    onPicture: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AkhuTopBar(stringResource(R.string.edit_profile), onBack) {
            TextButton(onClick = onSave, enabled = !saving) {
                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Avatar(avatar, 96.dp, Modifier.padding(top = Space.l).clickable(onClick = onPicture), avatarVersion)
            TextButton(onClick = onPicture) {
                Text(stringResource(R.string.edit_picture), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall)
            }
            Field(R.string.profile_name, name, onName, KeyboardCapitalization.Words)
            OutlinedTextField(
                value = username, onValueChange = onUsername, singleLine = true,
                label = { Text(stringResource(R.string.profile_username)) },
                isError = usernameError != null,
                supportingText = usernameError?.let { { Text(stringResource(it)) } },
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Field(R.string.profile_pronouns, pronouns, onPronouns, KeyboardCapitalization.None)
            OutlinedTextField(
                value = bio, onValueChange = onBio,
                label = { Text(stringResource(R.string.profile_bio)) },
                shape = MaterialTheme.shapes.small, minLines = 1, maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = Space.xl),
            )
        }
    }
}

@Composable
private fun Field(label: Int, value: String, onChange: (String) -> Unit, caps: KeyboardCapitalization) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(stringResource(label)) },
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(capitalization = caps, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

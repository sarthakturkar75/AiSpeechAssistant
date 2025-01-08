package com.example.aispeechassistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import android.util.Xml
import androidx.core.content.ContextCompat
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.dialogflow.v2.*
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import java.io.InputStream

class VoiceCommandService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    // Initialize Dialogflow sessions
    private lateinit var sessionsClient: SessionsClient
    private lateinit var session: SessionName
    private val projectId = "aispeechassistant-09aca399"

    override fun onCreate() {
        super.onCreate()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)

        textToSpeech = TextToSpeech(this, this)

        // Initialize Dialogflow
        initializeDialogflow()

        // Start listening immediately when the service is created
        startListening()
    }

    private fun initializeDialogflow() {
        try {
            val credentialsStream = resources.openRawResource(R.raw.credentials)
            val credentials = parseCredentialsFromXml(credentialsStream)

            val settings = SessionsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()

            sessionsClient = SessionsClient.create(settings)
            session = SessionName.of(projectId, "unique-session-id")
        } catch (e: Exception) {
            Log.e("VoiceCommandService", "Error initializing Dialogflow: ${e.message}")
        }
    }

    private fun parseCredentialsFromXml(inputStream: InputStream): GoogleCredentials {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        val credentialsMap = mutableMapOf<String, String>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                if (name != "credentials") {
                    parser.next()
                    credentialsMap[name] = parser.text
                }
            }
            eventType = parser.next()
        }

        val credentialsJson = """
            {
                "type": "${credentialsMap["type"]}",
                "project_id": "${credentialsMap["project_id"]}",
                "private_key_id": "${credentialsMap["private_key_id"]}",
                "private_key": "${credentialsMap["private_key"]}",
                "client_email": "${credentialsMap["client_email"]}",
                "client_id": "${credentialsMap["client_id"]}",
                "auth_uri": "${credentialsMap["auth_uri"]}",
                "token_uri": "${credentialsMap["token_uri"]}",
                "auth_provider_x509_cert_url": "${credentialsMap["auth_provider_x509_cert_url"]}",
                "client_x509_cert_url": "${credentialsMap["client_x509_cert_url"]}",
                "universe_domain": "${credentialsMap["universe_domain"]}"
            }
        """.trimIndent()

        return GoogleCredentials.fromStream(credentialsJson.byteInputStream())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognizer.destroy()
        textToSpeech.shutdown()
        sessionsClient.close()
        super.onDestroy()
    }

    /**
     * Continuously listens for any speech input.
     */
    private fun startListening() {
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.startListening(recognizerIntent)
    }

    //region RecognitionListener Callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("VoiceCommandService", "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        Log.d("VoiceCommandService", "Speech started")
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Not used for now
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        // Not used for now
    }

    override fun onEndOfSpeech() {
        Log.d("VoiceCommandService", "Speech ended")
        // Restart listening for continuous commands
        startListening()
    }

    override fun onError(error: Int) {
        val errorMessage = getErrorText(error)
        Log.e("VoiceCommandService", "Speech error: $errorMessage")
        // Restart listening if there's an error
        startListening()
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val command = matches?.getOrNull(0)?.lowercase(Locale.getDefault()) ?: ""

        Log.d("VoiceCommandService", "Command received: $command")

        // Send command to Dialogflow for intent recognition
        handleDialogflowResponse(command)

        // Continue listening after processing the command
        startListening()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        // Not used for now
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        // Not used for now
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error"
        }
    }
    //endregion

    //region TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.getDefault()
        } else {
            Log.e("VoiceCommandService", "TextToSpeech initialization failed.")
        }
    }
    //endregion

    /**
     * Handle Dialogflow response and perform actions based on intent.
     */
    private fun handleDialogflowResponse(command: String) {
        // Build the text input for Dialogflow
        val textInput = TextInput.newBuilder().apply {
            text = command
            languageCode = "en-US"
        }.build()
        val queryInput = QueryInput.newBuilder().setText(textInput).build()

        // Perform the detect intent request
        val response = sessionsClient.detectIntent(session, queryInput)
        val queryResult = response.queryResult

        when (queryResult.intent.displayName) {
            "OpenCameraIntent" -> openCamera()
            "CallContactIntent" -> {
                val contactName = queryResult.parameters.fieldsMap["contact"]?.stringValue ?: ""
                callContact(contactName)
            }
            "StopAssistantIntent" -> stopAssistant()
            else -> {
                textToSpeech.speak(
                    "I heard you say ${queryResult.queryText}, but I'm not sure how to handle that.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "SpeechID"
                )
            }
        }
    }

    /**
     * Launch the camera app.
     */
    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(cameraIntent)
        textToSpeech.speak("Opening camera.", TextToSpeech.QUEUE_FLUSH, null, "SpeechID")
    }

    /**
     * Make a phone call to a contact.
     */
    private fun callContact(contactName: String) {
        // Retrieve contact information
        val contactUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        val cursor = contentResolver.query(contactUri, projection, selection, selectionArgs, null)
        if (cursor != null && cursor.moveToFirst()) {
            val phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            cursor.close()

            // Create the intent to make the call
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check for multiple SIMs and prompt the user if necessary
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val phoneAccountHandles = telecomManager.callCapablePhoneAccounts

                if (phoneAccountHandles.size > 1) {
                    // Display a dialog to let the user choose a SIM card
                    val simPickerIntent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                        putExtra("com.android.phone.extra.slot", 0) // Example for SIM 1
                    }
                    startActivity(simPickerIntent)
                } else {
                    startActivity(callIntent)
                }
            } else {
                textToSpeech.speak("Permission to read phone state is required to make calls.", TextToSpeech.QUEUE_FLUSH, null, "SpeechID")
            }
            textToSpeech.speak("Calling $contactName.", TextToSpeech.QUEUE_FLUSH, null, "SpeechID")
        } else {
            textToSpeech.speak("Contact $contactName not found.", TextToSpeech.QUEUE_FLUSH, null, "SpeechID")
        }
    }

    /**
     * Stop the assistant.
     */
    private fun stopAssistant() {
        textToSpeech.speak("Goodbye!", TextToSpeech.QUEUE_FLUSH, null, "SpeechID")
        stopSelf()
    }
}
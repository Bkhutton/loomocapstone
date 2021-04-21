package com.example.rawvoice;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.renderscript.ScriptGroup;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiStreamObserver;
import com.google.api.gax.rpc.BidiStreamingCallable;
import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognizeRequest;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.cloud.speech.v1.SpeechSettings;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.StreamingRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.segway.robot.sdk.base.bind.ServiceBinder;
import com.segway.robot.sdk.voice.Recognizer;
import com.segway.robot.sdk.voice.VoiceException;
import com.segway.robot.sdk.voice.audiodata.RawDataListener;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.Lists;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    // Constant file path
    private final String FILE_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator;

    // Buttons to record audio and play the translation
    private Button startButton;
    private Button stopButton;
    private Button playButton;
    
    // TextView for translated text
    private TextView translationText;
    // Input text for people who don't want to speak to Loomo
    private EditText inputToTranslate;

    // Selector for target language to translate to
    private Spinner spinner;

    // Holds target language
    private String target;

    // Holds either the typed or spoken text
    private String originalText;

    // Holds translated text to be displayed in TextView
    private String translatedText;

    // If the internet is connected then True
    private boolean connected;

    // Segway Loomo objects needed to get raw data
    private RawDataListener mRawDataListener;
    private Recognizer mRecognizer;
    private ServiceBinder.BindStateListener mRecognitionBindStateListener;

    // Holds raw audio
    private File file;

    // Authentication classes to use Google Cloud API for translation and speech to text
    private GoogleCredentials myCredentials;
    private FixedCredentialsProvider credentialsProvider;

    // Used to perform speech to text
    private SpeechSettings speechSettings;

    // Used to perform translation
    private Translate translate;


    // Writes raw audio bytes to file
    public void writeByte(byte[] data) {
        try {
            OutputStream os = new FileOutputStream(file, true);
            os.write(data);
            os.close();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    // Clears file contents
    public void clearFile() {
        try{
            OutputStream os = new FileOutputStream(file);
            os.close();
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    // Returns the raw audio bytes from the raw audio file
    public static byte[] getAudioBytes(InputStream inputStream){
        byte[] bytes = new byte[0];
        try {
            bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bytes;
    }

    // Initializes Google Cloud API speech to text services
    public void getSpeechService() {

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // R.raw.loomocapstone is the json key you get from the Google Cloud API Account
        // Put the json key in the /res/raw folder
        try (InputStream is = getResources().openRawResource(R.raw.loomocapstone)) {

            //Get credentials:
            myCredentials = GoogleCredentials.fromStream(is);

            credentialsProvider = FixedCredentialsProvider.create(myCredentials);
            speechSettings = SpeechSettings.newBuilder().setCredentialsProvider(credentialsProvider).build();

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    // Initializes Google Cloud API Translation services
    public void getTranslateService() {

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // R.raw.loomocapstone is the json key you get from the Google Cloud API Account
        // Put the json key in the /res/raw folder
        try (InputStream is = getResources().openRawResource(R.raw.loomocapstone)) {

            //Get credentials:
            final GoogleCredentials myCredentials = GoogleCredentials.fromStream(is);

            //Set credentials and get translate service:
            TranslateOptions translateOptions = TranslateOptions.newBuilder().setCredentials(myCredentials).build();
            translate = translateOptions.getService();

        } catch (IOException ioe) {
            ioe.printStackTrace();

        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize screen entities
        translationText = findViewById(R.id.textView);
        inputToTranslate = findViewById(R.id.inputToTranslate);
        spinner = findViewById(R.id.Spinner1);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.numbers, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // Initialize raw audio file
        file = new File(FILE_PATH + "audio.raw");

        // Initialize speech to text service
        getSpeechService();

        // Initialize translation service
        getTranslateService();

        // Initialize Loomo recognizer
        mRecognizer = Recognizer.getInstance();

        // Enable beam forming allows raw audio to be recorded
        /*
        This isn't needed but is commented in case needed for future project developments
        try {
            mRecognizer.beamForming(true);
        } catch (VoiceException e) {
            e.printStackTrace();
        }
         */

        // Initialize buttons and listeners
        initButtons();
        initListeners();

        // Binds Loomo recognizer service
        mRecognizer.bindService(getApplicationContext(), mRecognitionBindStateListener);
    }

    private void initButtons() {

        // Sets button variables
        startButton = (Button) findViewById(R.id.start_button);
        stopButton = (Button) findViewById(R.id.stop_button);
        stopButton.setEnabled(false);
        playButton = (Button) findViewById(R.id.play);

        // Handlers buttons clicks
        startButton.setOnClickListener(this::onClick);
        stopButton.setOnClickListener(this::onClick);
        playButton.setOnClickListener(this::onClick);
    }

    private void initListeners() {

        // Can customize what happens when the recognition listener is binded
        mRecognitionBindStateListener = new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {

            }

            @Override
            public void onUnbind(String reason) {

            }
        };


        // RawDataListener is where the raw audio is sent
        mRawDataListener = new RawDataListener() {
            @Override
            public void onRawData(byte[] data, int dataLength) {
                // Writes the raw audio to file
                writeByte(data);
            }
        };
    }

    // Button handlers
    public void onClick(View view) {
        // Start Button Push
        if (view.getId() == R.id.start_button) {
            // Ensures only one speech at a time gets recorded
            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            // Clears file contents to remove last translation
            clearFile();

            // Begins recording
            try {
                mRecognizer.startBeamFormingListen(mRawDataListener);
            } catch (VoiceException e) {
                e.printStackTrace();
            }
        }
        // Stop Button Push
        else if (view.getId() == R.id.stop_button) {
            //Ensures only one speech at a time gets recorded
            startButton.setEnabled(true);
            stopButton.setEnabled(false);

            // Stops recording
            try {
                mRecognizer.stopBeamFormingListen();
            } catch (VoiceException e) {
                e.printStackTrace();
            }
        }
        // Translates
        else if (view.getId() == R.id.play){

            try {
                // Gets raw audio file contents
                System.out.println(inputToTranslate.getText().toString());
                if(inputToTranslate.getText().toString().equals("")) {
                InputStream inputStream = new FileInputStream(file);

                // Performs speech to text and translates
                sampleRecognize(inputStream); }
                else {
                    translate("");
                }
                spinner.setSelection(0);
            } catch (Exception e) {
                e.printStackTrace();
            }


        }
    }

    /**
     * Transcribe a short audio file using synchronous speech recognition
     *
     */
    public void sampleRecognize(InputStream inputStream) {
        try (SpeechClient speechClient = SpeechClient.create(speechSettings)) {

            // The language of the supplied audio
            String languageCode = "en-US";

            // Sample rate in Hertz of the audio data sent
            int sampleRateHertz = 16000;

            // Encoding of audio data sent. This sample sets this explicitly.
            // This field is optional for FLAC and WAV audio formats.
            RecognitionConfig.AudioEncoding encoding = RecognitionConfig.AudioEncoding.LINEAR16;
            RecognitionConfig config =
                    RecognitionConfig.newBuilder()
                            .setLanguageCode(languageCode)
                            .setSampleRateHertz(sampleRateHertz)
                            .setEncoding(encoding)
                            .build();

            byte[] data = getAudioBytes(inputStream);

            ByteString content = ByteString.copyFrom(data);
            RecognitionAudio audio = RecognitionAudio.newBuilder().setContent(content).build();
            RecognizeRequest request =
                    RecognizeRequest.newBuilder().setConfig(config).setAudio(audio).build();
            RecognizeResponse response = speechClient.recognize(request);
            System.out.println(response.getResultsList().toString());
            for (SpeechRecognitionResult result : response.getResultsList()) {
                // First alternative is the most probable result
                SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
                System.out.printf("Transcript: %s\n", alternative.getTranscript());
                if (checkInternetConnection()) {

                    //If there is internet connection, get translate service and start translation:
                    getTranslateService();
                    translate(alternative.getTranscript());

                } else {

                    //If not, display "no connection" warning:
                    translationText.setText(getResources().getString(R.string.no_connection));
                }
            }
        } catch (Exception exception) {
            System.err.println("Failed to create the client due to: " + exception);
        }
    }

    // Performs translation on given text
    public void translate(String text) {
        //Get input text to be translated:
        if(text == "" || text == null) {
            originalText = inputToTranslate.getText().toString();
        }
        else {
            originalText = text;
        }
        Translation translation = translate.translate(originalText, Translate.TranslateOption.targetLanguage(target), Translate.TranslateOption.model("base"));
        translatedText = translation.getTranslatedText();

        //Translated text and original text are set to TextViews:
        translationText.setText(translatedText);
        inputToTranslate.setText("");
    }

    // Checks for internet connection to use Google Cloud APIs
    public boolean checkInternetConnection() {

        //Check internet connection:
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        //Means that we are connected to a network (mobile or wi-fi)
        connected = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState() == NetworkInfo.State.CONNECTED ||
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState() == NetworkInfo.State.CONNECTED;

        return connected;
    }

    // Sets target language from the spinner drop-down
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        String text = parent.getItemAtPosition(position).toString();
        ((TextView) parent.getChildAt(0)).setTextColor(Color.WHITE);
            /* This is to make the selection appear for a couple seconds at the bottom of the screen
        Toast.makeText(parent.getContext(),text, Toast.LENGTH_SHORT).show();
            */
        switch(position) {
            case 0:
                target="en";
                break;
            case 1:
                target="es";
                break;
            case 2:
                target="en";
                break;
            case 3:
                target="it";
                break;
            case 4:
                target="fr";
                break;
            case 5:
                target="de";
                break;
            default:
                target="Error";
                break;
        }


    }
    public void onNothingSelected(AdapterView<?> parent) {

    }
}


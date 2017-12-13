/*
* Copyright 2017 Google Inc.
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*/

package com.google.example.babelfirelive;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.ResultCodes;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final Map<String, String> LANGUAGE_CODES = new HashMap<String, String>() {{
        put("English (United States)", "en-US");
        put("Español (España)", "es-ES");
        put("Português (Brasil)", "pt-BR");
        put("Deutsch (Deutschland)", "de-DE");
        put("日本語（日本）", "ja-JP");
        put("हिन्दी (भारत)", "hi-IN");
        put("Nederlands (Nederland)", "nl-NL");
        put("French (France)", "fr-FR");
        put("Polish (Poland)", "pl-PL");
    }};
    private static final Map<String, String> LANGUAGE_SHORT_CODES = new HashMap<String, String>() {{
        put("English (United States)", "en");
        put("Español (España)", "es");
        put("Português (Brasil)", "pt");
        put("Deutsch (Deutschland)", "de");
        put("日本語（日本）", "ja");
        put("हिन्दी (भारत)", "hi");
        put("Nederlands (Nederland)", "nl");
        put("French (France)", "fr");
        put("Polish (Poland)", "pl");
    }};

    public static final short RC_SIGN_IN = 42;
    public static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private MediaRecorder mRecorder;
    private boolean mIsRecording = false;
    private File mRecording;
    private TextView mTranslatedText;
    private TextToSpeech mTextToSpeech;
    private boolean mTextToSpeechEnabled = false;
    private Spinner mLanguageChoices;

    private String mCurKey;

    private String mLangCode;
    private String mShortLangCode;

    private ValueEventListener mCurrentLanguageListener;

    private FirebaseStorage mStorage;
    private FirebaseAuth mAuth;
    private FirebaseFirestore mFirestore;

    private Button mRecordButton;
    private View.OnClickListener mRecordButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            try {
                final Button recordButton = mRecordButton;

                if (!mIsRecording) {
                    // Disable button to keep from crashing app with multiple entries
                    recordButton.setEnabled(false);

                    mRecorder = new MediaRecorder();
                    mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    mRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
                    mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

                    File outputDir = MainActivity.this.getCacheDir();
                    mRecording = File.createTempFile("audio", ".amr", outputDir);

                    Log.d(TAG, "Recording to " + mRecording);

                    mRecorder.setOutputFile(mRecording.getPath());
                    mRecorder.prepare();
                    mRecorder.start();
                    recordButton.setText(R.string.stop_recording);

                    mIsRecording = true;

                    // There's no clean way to check if it's safe to start or stop, so hope
                    // it's clear in 800ms
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            recordButton.setEnabled(true);
                        }
                    }, 800);

                } else {
                    // Recording done clean up UI
                    mRecorder.stop();
                    mRecorder.reset();
                    mRecorder.release();
                    mRecorder = null;

                    recordButton.setText(R.string.record);

                    mIsRecording = false;

                    // Upload the file to Firebase Storage
                    uploadFile();
                }

            } catch (IOException e) {
                Log.e(TAG, "could not record", e);
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Fetch views we need access to
        mLanguageChoices = (Spinner) findViewById(R.id.langauge_choices);
        mTranslatedText = (TextView) findViewById(R.id.translated_text);
        Button signOutButton = (Button) findViewById(R.id.sign_out_button);

        // Init spinner handler
        mLanguageChoices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Get the language from the spinner
                mLangCode = LANGUAGE_CODES.get(mLanguageChoices.getSelectedItem());
                mShortLangCode = LANGUAGE_SHORT_CODES.get(mLanguageChoices.getSelectedItem());
                listenForLanguage(mCurKey, mShortLangCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Init text to speech
        mTextToSpeech = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Text to speech enabled");
                    mTextToSpeechEnabled = true;
                }
            }
        });

        signOutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AuthUI.getInstance()
                    .signOut(MainActivity.this)
                    .addOnCompleteListener(new OnCompleteListener<Void>() {
                        public void onComplete(@NonNull Task<Void> task) {
                            // user is now signed out
                            startActivity(new Intent(getApplicationContext(), MainActivity.class));
                            finish();
                        }
                    });
            }
        });

        // Record button stuff
        mRecordButton = (Button) findViewById(R.id.record_button);
        mRecordButton.setOnClickListener(mRecordButtonListener);
        if (ContextCompat.checkSelfPermission(this, "android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.RECORD_AUDIO"}, MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
        }

        // TODO 1: Set up Firebase
        mStorage = FirebaseStorage.getInstance();
        mAuth = FirebaseAuth.getInstance();
        mFirestore = FirebaseFirestore.getInstance();

        // TODO 5: Add authentication
        if (mAuth.getCurrentUser() == null) {
            // TODO 6: Initiate signin flow
            startActivityForResult(
                    AuthUI.getInstance()
                            .createSignInIntentBuilder()
                            .setProviders(Arrays.asList(new AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                                    new AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build()))
                            .build(),
                    RC_SIGN_IN);
        } else {
            // Time to listen for new translations.
            listenForTranslations();
        }

    }

    private void uploadFile() {
        // TODO 2: Get storage ref, and build metadata
        StorageReference uploadRef = mStorage.getReference().child("uploads");
        final DocumentReference docRef = mFirestore.collection("uploads").document();

        StorageReference uploadFile = uploadRef.child(docRef.getId() + ".amr");
        StorageMetadata uploadMetadata = new StorageMetadata.Builder()
                .setContentType("audio/amr")
                .build();

        // TODO 3: Upload the file
        uploadFile.putFile(Uri.fromFile(mRecording), uploadMetadata)

        // TODO 4: Add completion listeners, write to Firestore
                .addOnFailureListener(new OnFailureListener() {
                    public void onFailure(@NonNull Exception e) {
                        toast("Failed to upload audio :(");
                    }})
                .addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                        toast("Uploaded!");

                        Recording recording = new Recording(mRecording.getName(), mLangCode,
                                taskSnapshot.getDownloadUrl().toString(),
                                taskSnapshot.getStorage().getPath().substring(1),
                                taskSnapshot.getTotalByteCount());

                        docRef.set(recording)
                                .addOnSuccessListener(new OnSuccessListener<Void>() {
                                    @Override
                                    public void onSuccess(Void aVoid) {
                                        toast("Successfully written!");
                                    }
                                })
                                .addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        toast("Error writing document");
                                    }
                                });
                    }
                });

    }

    private void listenForTranslations() {
        // TODO 7: Get a reference to translations
        CollectionReference translationsRef = mFirestore.collection("translations");

        // TODO 8: Listen to last child added
        translationsRef.orderBy("timestamp", Query.Direction.DESCENDING).limit(1).addSnapshotListener(new EventListener<QuerySnapshot>() {
            @Override
            public void onEvent(QuerySnapshot documentSnapshots, FirebaseFirestoreException e) {
                if (documentSnapshots != null) {
                    List<DocumentSnapshot> docs = documentSnapshots.getDocuments();
                    if (!docs.isEmpty()) {
                        DocumentSnapshot doc = docs.get(0);
                        mCurKey = doc.getId();
                        listenForLanguage(mCurKey, mShortLangCode);
                    } else {
                        toast("No document returned");
                    }
                } else {
                    toast("No such document");
                }
            }
        });

    }

    private void listenForLanguage(String translationKey, final String languageCode) {
        if(translationKey != null && languageCode != null) {
            // TODO 9: Get a particular translation
            DocumentReference languageRef = mFirestore.collection("translations").document(translationKey);
            languageRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document != null) {
                            Map<String, Object> data = document.getData();
                            Map<String, String> languages = (Map<String, String>) data.get("languages");
                            String translation = languages.get(languageCode);
                            toast("translation " + translation);
                            updateAndPlay(translation, languageCode);
                        } else {
                            Log.d(TAG, "No such document");
                        }
                    } else {
                        Log.d(TAG, "get failed with ", task.getException());
                    }
                }
            });
        }
    }

    private void updateAndPlay(String translatedText, String languageCode) {
        // Display
        mTranslatedText.setText(translatedText);

        // Say
        if (mTextToSpeechEnabled) {
            mTextToSpeech.setLanguage(Locale.forLanguageTag(languageCode));
            mTextToSpeech.speak(translatedText, TextToSpeech.QUEUE_ADD, null, null);
        }
    }

    private void toast(String message) {
        Toast.makeText(MainActivity.this, message,
            Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            IdpResponse response = IdpResponse.fromResultIntent(data);

            if (resultCode == ResultCodes.OK) {
                // Successfully signed in
                Intent intent = IdpResponse.getIntent(response);
                intent.setClass(this, MainActivity.class);
                startActivity(intent);
                finish();
            } else {
                // Sign in failed
                toast("Sign in failed :(");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
                if (grantResults.length > 0  && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    toast("permission granted");
                } else {
                    toast("permission denied");
                }
                return;
            }
        }
    }
}

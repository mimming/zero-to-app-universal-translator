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
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
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
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class StartActivity extends AppCompatActivity {

    private static final String TAG = StartActivity.class.getSimpleName();

    private static final Map<String, String> LANGUAGE_CODES = new HashMap<String, String>() {{
        put("English (United States)", "en-US");
        put("Español (España)", "es-ES");
        put("Português (Brasil)", "pt-BR");
        put("Deutsch (Deutschland)", "de-DE");
        put("日本語（日本）", "ja-JP");
        put("हिन्दी (भारत)", "hi-IN");
        put("Nederlands (Nederland)", "nl-NL");
    }};
    private static final Map<String, String> LANGUAGE_SHORT_CODES = new HashMap<String, String>() {{
        put("English (United States)", "en");
        put("Español (España)", "es");
        put("Português (Brasil)", "pt");
        put("Deutsch (Deutschland)", "de");
        put("日本語（日本）", "ja");
        put("हिन्दी (भारत)", "hi");
        put("Nederlands (Nederland)", "nl");
    }};

    public static final short RC_SIGN_IN = 42;

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
    private FirebaseDatabase mDatabase;
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

                    File outputDir = StartActivity.this.getCacheDir();
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
                    .signOut(StartActivity.this)
                    .addOnCompleteListener(new OnCompleteListener<Void>() {
                        public void onComplete(@NonNull Task<Void> task) {
                            // user is now signed out
                            startActivity(new Intent(getApplicationContext(), StartActivity.class));
                            finish();
                        }
                    });
            }
        });

        // Record button stuff
        mRecordButton = (Button) findViewById(R.id.record_button);
        mRecordButton.setOnClickListener(mRecordButtonListener);

        // 1 Set up Firebase
        mDatabase = FirebaseDatabase.getInstance();
        mStorage = FirebaseStorage.getInstance();
        mAuth = FirebaseAuth.getInstance();
        mFirestore = FirebaseFirestore.getInstance();

        // 5 Set up FirebaseUI
        if (mAuth.getCurrentUser() == null) {
            // 6 Fire up the Firebase-UI Auth activity
            startActivityForResult(
                    AuthUI.getInstance()
                            .createSignInIntentBuilder()
                            .setProviders(Arrays.asList(
                                    new AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                                    new AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build()))
                            .build(),
                    RC_SIGN_IN);
            finish();
        } else {
            // logged in.  Time to listen for new translations.
            listenForTranslations();
        }
    }

    private void uploadFile() {
        // 2 Get storage ref, and build metadata
        StorageReference uploadRef = mStorage.getReference().child("uploads");

        StorageReference uploadFile = uploadRef.child("foo" + ".amr");

        StorageMetadata uploadMetadata = new StorageMetadata.Builder()
                .setContentType("audio/amr")
                .build();

        // 3-4 Upload the file, and handle failure
        uploadFile.putFile(Uri.fromFile(mRecording), uploadMetadata)
                .addOnFailureListener(new OnFailureListener() {
                    public void onFailure(@NonNull Exception e) {
                        toast("Failed to upload audio :(");
                    }}).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
            public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                toast("Uploaded!");

                Recording recording = new Recording(
                        mRecording.getName(),
                        mLangCode,
                        taskSnapshot.getDownloadUrl().toString(),
                        taskSnapshot.getStorage().getPath().substring(1),
                        taskSnapshot.getTotalByteCount()
                );

//                final DatabaseReference dbRef = mDatabase.getReference("uploads").push();
//                dbRef.setValue(recording);
                mFirestore.collection("uploads").add(recording).addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(DocumentReference documentReference) {
                        toast("DB write succeeded!");
                    }
                }).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        toast("DB write failed!");
                    }
                });
            }
        });
    }

    private void listenForTranslations() {
        // 7 Get a reference to translations
//        DatabaseReference translationsRef = mDatabase.getReference().child("translations");
//        CollectionReference translationsRef = mFirestore.collection("translations");

        // 8 Listen to last child added
//        translationsRef.limitToLast(1).addChildEventListener(new ChildEventListener() {
//            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
//                mCurKey = dataSnapshot.getKey();
//                listenForLanguage(mCurKey, mShortLangCode);
//            }
//
//            public void onChildChanged(DataSnapshot dataSnapshot, String s) { }
//            public void onChildRemoved(DataSnapshot dataSnapshot) { }
//            public void onChildMoved(DataSnapshot dataSnapshot, String s) { }
//            public void onCancelled(DatabaseError databaseError) { }
//        });
        CollectionReference translationsRef = mFirestore.collection("translations");
        translationsRef.orderBy("timestamp").limit(1).get().addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
            @Override
            public void onComplete(@NonNull Task<QuerySnapshot> task) {
                if (task.isSuccessful()) {
                    QuerySnapshot document = task.getResult();
                    if (document != null) {
                        Log.d(TAG, "DocumentSnapshot data: " + task.getResult().getDocuments());
                    } else {
                        Log.d(TAG, "No such document");
                    }
                } else {
                    Log.d(TAG, "get failed with ", task.getException());
                }
            }
        });
    }

    private void listenForLanguage(String translationKey, final String languageCode) {
        System.out.println("translation key: " + translationKey + " language code: " + languageCode);
        if(translationKey != null && languageCode != null) { // 9-10
            DatabaseReference langRef = mDatabase.getReference().child("translations")
                    .child(translationKey).child(languageCode).child("text");

            if (mCurrentLanguageListener != null) {
                langRef.removeEventListener(mCurrentLanguageListener);
            }

            mCurrentLanguageListener = langRef.addValueEventListener(new ValueEventListener() {
                public void onDataChange(DataSnapshot dataSnapshot) {
                    String translation = dataSnapshot.getValue(String.class);
                    updateAndPlay(translation, languageCode);
                }

                public void onCancelled(DatabaseError databaseError) { }
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
        Toast.makeText(StartActivity.this, message,
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
                intent.setClass(this, StartActivity.class);
                startActivity(intent);
                finish();
            } else {
                // Sign in failed
                toast("Sign in failed :(");
            }
        }
    }
}

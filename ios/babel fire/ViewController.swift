//
//  Copyright (c) 2017 Google Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

import AVFoundation
import UIKit

import Firebase
import FirebaseAuthUI
import FirebaseGoogleAuthUI

class ViewController: UIViewController, UIPickerViewDataSource, UIPickerViewDelegate, AVAudioRecorderDelegate, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate, FUIAuthDelegate {

    // Global mutable state zomg evil
    var selectedLanguage: String = "en"
    let fileName = "recording.caf"
    var isRecording = false
    let languageArray = [
        "en",
        "es",
        "pt",
        "de",
        "ja",
        "hi",
        "nl"
    ]
    let languageDict = [
        "en": [
            "name": "English (United States)",
            "locale": "en-US"
        ],
        "es": [
            "name": "Español (España)",
            "locale": "es-ES"
        ],
        "pt": [
            "name": "Português (Brasil)",
            "locale": "pt-BR"
        ],
        "de": [
            "name": "Deutsch (Deutschland)",
            "locale": "de-DE"
        ],
        "ja": [
            "name": "日本語（日本)",
            "locale": "ja-JP"
        ],
        "hi": [
            "name": "हिन्दी (भारत)",
            "locale": "hi-IN"
        ],
        "nl": [
            "name": "Nederlands (Nederland)",
            "locale": "nl-NL"
        ]
    ] as [String : [String: String]]

    // UI elements
    @IBOutlet weak var languagePicker: UIPickerView!
    @IBOutlet weak var recordButton: UIButton!
    @IBOutlet weak var translatedTextView: UITextView!

    // AVAudioRecorder and Player
    var recordingSession: AVAudioSession!
    var audioRecorder: AVAudioRecorder!
    var audioPlayer: AVAudioPlayer!
    var synthesizer: AVSpeechSynthesizer!

    // Firebase
    var storage: Storage!
    var database: Firestore!
    var auth: Auth!

    var authUI: FUIAuth?

    // Activity lifecycle
    override func viewDidLoad() {
        super.viewDidLoad()

        // Set up navigation controller
        self.title = "BabelFire"
        self.navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Log in", style: UIBarButtonItemStyle.plain, target: self, action: #selector(loginButtonPressed))
        self.recordButton.isEnabled = false

        // Set up picker view
        languagePicker.dataSource = self
        languagePicker.delegate = self

        // Set up AVAudio*
        recordingSession = AVAudioSession.sharedInstance()
        do {
            try recordingSession.setCategory(AVAudioSessionCategoryPlayAndRecord)
            try recordingSession.setActive(true)
            recordingSession.requestRecordPermission({ (allowed) in
                if allowed {
                    self.recordButton.isEnabled = true
                }
            })
        } catch {
            print("Failed to get permission: \(error)")
        }
        self.synthesizer = AVSpeechSynthesizer()
        self.synthesizer.delegate = self

        // TODO 1: Set up Firebase (1)
        // Set up Firebase
        self.storage = Storage.storage()
        self.auth = Auth.auth()
        self.database = Firestore.firestore()

        // TODO: init FirebaseUI Auth (5)
        // Set up FirebaseUI
        self.authUI = FUIAuth.defaultAuthUI()
        self.authUI?.delegate = self
    }

    override func viewWillAppear(_ animated: Bool) {
        // TODO: show sign in if user isn't signed in (6)
        self.authUI?.providers = [
            FUIGoogleAuth()
        ]
        if (self.auth.currentUser == nil) {
            // No current user, so show a sign in view
            self.navigationItem.rightBarButtonItem?.title = "Log in"
            let authViewController = self.authUI?.authViewController()
            self.present(authViewController!, animated: true, completion: nil)
        } else {
            self.navigationItem.rightBarButtonItem?.title = "Log out"
            listenForTranslations()
        }
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // Dispose of any resources that can be recreated.
    }

    // UIPickerViewDataSource
    func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int {
        return languageArray.count
    }

    func numberOfComponents(in pickerView: UIPickerView) -> Int {
        return 1;
    }

    // UIPickerViewDelegate
    func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
        return languageDict[languageArray[row]]?["name"]
    }

    func pickerView(_ pickerView: UIPickerView, didSelectRow row: Int, inComponent component: Int) {
        self.selectedLanguage = languageArray[row]
    }

    // Handle record button
    @IBAction func recordButtonPressed(_ sender: AnyObject) {
        isRecording = !isRecording;

        if (isRecording == true) {
            startRecording()
            recordButton.setTitle("Stop", for: UIControlState())
        } else {
            stopRecording()
            recordButton.setTitle("Record", for: UIControlState())
        }
    }

    func startRecording() {
        let file = documentsDirectory().appendingPathComponent(fileName)

        let settings = [
            AVSampleRateKey: 16000.0 as NSNumber,
            AVNumberOfChannelsKey: 1  as NSNumber,
            AVEncoderBitRateKey: 16  as NSNumber
        ] as [String: Any]

        do {
            self.audioRecorder = try AVAudioRecorder(url: file, settings: settings)
            self.audioRecorder.delegate = self
            self.audioRecorder.record()
        } catch {
            print("Failed to get audio recorder \(error)")
        }
    }

    func stopRecording() {
        self.audioRecorder.stop()
        self.audioRecorder = nil
    }

    func documentsDirectory() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
    }

    // AVAudioRecorderDelegate
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        // Check if the file was successfully uploaded
        if (!flag) {
            print("Failed to finish recording")
            return
        }

        uploadFile()
    }

    func uploadFile() {
        let file = documentsDirectory().appendingPathComponent(fileName)

        // TODO: upload file and write to database (2-4)
        // Get storage reference and build metadata
        let uploadRef = self.storage.reference().child("uploads")
        let dbRef = self.database.collection("uploads").document()
        let uploadFile = uploadRef.child(dbRef.documentID)
        let metadata = StorageMetadata()
        metadata.contentType = "LINEAR16"

        // Upload the file
        uploadFile.putFile(from: file, metadata: metadata) { (metadata, error) in
            // handle failure
            if (error != nil) {
                self.toast(message: "Failed to upload audio: \(String(describing: error))")
            } else {
                self.toast(message: "Uploaded!")
                let dict = [
                    "encoding": "LINEAR16",
                    "sampleRate": 16000,
                    "language": (self.languageDict[self.selectedLanguage]?["locale"])! as String,
                    "fullPath": "uploads/\(dbRef.documentID)"
                    //"fullPath": "uploads/\(dbRef.key)"
                ] as [String : Any]
                dbRef.setData(dict, completion: { error in
                    if error != nil {
                        self.toast(message: "Failed to write to the database: \(String(describing: error))")
                    }
                    
                })
                self.play()
            }
        }
    }

    // for testing purposes only. Remove later
    func  play() {
        let fileURL = documentsDirectory().appendingPathComponent(fileName)
        do {
            audioPlayer = try AVAudioPlayer(contentsOf: fileURL as URL)
            audioPlayer.delegate = self as! AVAudioPlayerDelegate
            if audioPlayer.duration > 0.0 {
                //setPlayButtonOn(flag: true)
                audioPlayer.play()
            }
        } catch {
            print("Error loading audioPlayer.")
        }
    }

    
    // Handle login button
    @objc func loginButtonPressed(_ sender: AnyObject) {
        if (self.auth.currentUser != nil) {
            do {
                try self.auth.signOut()
                self.navigationItem.rightBarButtonItem?.title = "Log in"
            } catch {
                print("Failed to sign user out: \(error)")
            }
        } else {
            // Require log in flow
            self.navigationItem.rightBarButtonItem?.title = "Log out"
            let authViewController = self.authUI?.authViewController()
            self.present(authViewController!, animated: true, completion: nil)
        }
    }

    // FUIAuthDelegate
    func authUI(_ authUI: FUIAuth, didSignInWith user: User?, error: Error?) {
        if (error != nil) {
            print("Failed to sign user in: \(String(describing: error))")
            return
        }
    }

    func listenForTranslations() {
        // TODO: listen for new translations (7-8)
        // Get a reference to translations
        let translationsRef = self.database.collection("translations")
        //let translationsRef = self.database.reference().child("translations")

        // Listen to last child added
        translationsRef.limit(to: 1).addSnapshotListener({ (snapshot, error) in
            if let error = error {
                print(error)
            } else {
                if (snapshot!.documents.count) > 0 {
                    self.listenForLanguage(translationRef: (snapshot?.documents.first?.reference)!, languageCode: self.selectedLanguage)
                }
            }
        })
        
    }

    func listenForLanguage(translationRef: DocumentReference, languageCode: String) {
        // TODO: wait for our language (9-10)
        // Wait for our language to appear
        let languageRef = translationRef.collection("languages").document(languageCode)

        languageRef.getDocument(completion: {(document, error) in
            guard let data = document?.data() as? [String: String] else {print("failure"); return}
            // Play the translation through the local text-to-speech
            let translation = data["text"]
            self.updateAndPlay(text: translation!)
        })
    }

    func updateAndPlay(text: String) {
        self.translatedTextView.text = text

        let locale = self.languageDict[self.selectedLanguage]?["locale"]
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: locale)
        self.synthesizer.speak(utterance)
    }

    // AVSpeechSynthesizerDelegate
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        self.synthesizer.stopSpeaking(at: .immediate)
    }

    func toast(message: String) {
        toast(message: message, interval: 1.0)
    }
    func toast(message: String, interval: TimeInterval) {
        let alertController = UIAlertController(title: "babelfire", message: message, preferredStyle: .alert)
        present(alertController, animated: true, completion: nil)
        self.perform(#selector(dismissAlertViewController), with: alertController, afterDelay: interval)
    }

    @objc func dismissAlertViewController(alertController: UIAlertController) {
        alertController.dismiss(animated: true, completion: nil)
    }

}

// רכיב זיהוי דיבור למק: מקשיב למיקרופון עם זיהוי הדיבור המובנה של macOS (SFSpeechRecognizer)
// ומדפיס את הטקסט כשורות JSON - אותו פורמט שהגשר של Edge שולח ב-Windows.
//
// פקודות (stdin, שורה לכל פקודה):   start he-IL | stop | quit
// פלט (stdout):  {"type":"status","state":"ready|listening|error","msg":"..."}  |  {"type":"text","text":"..."}
// בדיקה:        tp-speech --check   (מדפיס אם העברית נתמכת ויוצא)
import AVFoundation
import Foundation
import Speech

setvbuf(stdout, nil, _IOLBF, 0)

func emit(_ obj: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: obj),
          let line = String(data: data, encoding: .utf8) else { return }
    print(line)
    fflush(stdout)
}

func status(_ state: String, _ msg: String) { emit(["type": "status", "state": state, "msg": msg]) }

if CommandLine.arguments.contains("--check") {
    let locales = SFSpeechRecognizer.supportedLocales().map { $0.identifier }.sorted()
    let he = locales.contains { $0.hasPrefix("he") || $0.hasPrefix("iw") }
    print("supported locales: \(locales.count)")
    print("hebrew: \(he ? "yes" : "no")  (\(locales.filter { $0.hasPrefix("he") || $0.hasPrefix("iw") }.joined(separator: ", ")))")
    if let r = SFSpeechRecognizer(locale: Locale(identifier: "he-IL")) {
        print("he-IL recognizer: available=\(r.isAvailable) onDevice=\(r.supportsOnDeviceRecognition)")
    } else {
        print("he-IL recognizer: nil")
    }
    exit(he ? 0 : 2)
}

final class Listener {
    private let engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var taskId = 0
    private var running = false
    private var rolloverTimer: Timer?

    func start(localeId: String) {
        stop(quiet: true)
        guard let r = SFSpeechRecognizer(locale: Locale(identifier: localeId)) else {
            status("error", "השפה \(localeId) לא נתמכת בזיהוי הדיבור של macOS")
            return
        }
        guard r.isAvailable else {
            status("error", "זיהוי הדיבור של macOS לא זמין כרגע - בדוק חיבור לאינטרנט")
            return
        }
        recognizer = r
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            status("error", "לא נמצא מיקרופון")
            return
        }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        engine.prepare()
        do { try engine.start() } catch {
            status("error", "לא הצלחתי לפתוח את המיקרופון: \(error.localizedDescription)")
            return
        }
        running = true
        newTask()
        status("listening", "מקשיב")
    }

    // בקשת זיהוי מול השרת מוגבלת לכדקה - מחליפים בקשה כל 50 שניות, או כשהזיהוי מסיים משפט
    private func newTask() {
        guard running, let recognizer = recognizer else { return }
        task?.cancel()
        request?.endAudio()
        taskId += 1
        let myId = taskId
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if #available(macOS 13, *) { req.addsPunctuation = true }
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self = self, myId == self.taskId else { return }   // תוצאה מבקשה ישנה
                if let result = result {
                    emit(["type": "text", "text": result.bestTranscription.formattedString])
                    if result.isFinal { self.newTask(); return }
                }
                if error != nil, self.running {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                        if myId == self.taskId { self.newTask() }
                    }
                }
            }
        }
        rolloverTimer?.invalidate()
        rolloverTimer = Timer.scheduledTimer(withTimeInterval: 50, repeats: false) { [weak self] _ in
            guard let self = self, myId == self.taskId else { return }
            self.newTask()
        }
    }

    func stop(quiet: Bool = false) {
        let was = running
        running = false
        rolloverTimer?.invalidate()
        taskId += 1
        task?.cancel()
        request?.endAudio()
        task = nil
        request = nil
        if engine.isRunning {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        if was && !quiet { status("ready", "מושהה") }
    }
}

let listener = Listener()
var authorized = false
var pendingLocale: String?

func handle(_ line: String) {
    let parts = line.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ").map(String.init)
    guard let cmd = parts.first else { return }
    switch cmd {
    case "start":
        let loc = parts.count > 1 ? parts[1] : "he-IL"
        if authorized { listener.start(localeId: loc) } else { pendingLocale = loc }
    case "stop":
        pendingLocale = nil
        listener.stop()
    case "quit":
        listener.stop(quiet: true)
        exit(0)
    default:
        break
    }
}

// הרשאות: מיקרופון + זיהוי דיבור. macOS מציג את הבקשה בשם האפליקציה.
AVCaptureDevice.requestAccess(for: .audio) { micOK in
    SFSpeechRecognizer.requestAuthorization { auth in
        DispatchQueue.main.async {
            if !micOK {
                status("error", "אין הרשאה למיקרופון - אשר בהגדרות המערכת > פרטיות ואבטחה > מיקרופון")
            } else if auth != .authorized {
                status("error", "אין הרשאה לזיהוי דיבור - אשר בהגדרות המערכת > פרטיות ואבטחה > זיהוי דיבור")
            } else {
                authorized = true
                status("ready", "מוכן")
                if let loc = pendingLocale { pendingLocale = nil; listener.start(localeId: loc) }
            }
        }
    }
}

FileHandle.standardInput.readabilityHandler = { fh in
    let data = fh.availableData
    if data.isEmpty { exit(0) }   // האפליקציה נסגרה
    guard let text = String(data: data, encoding: .utf8) else { return }
    for line in text.split(separator: "\n") {
        let l = String(line)
        DispatchQueue.main.async { handle(l) }
    }
}

RunLoop.main.run()

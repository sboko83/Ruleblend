import Foundation
@preconcurrency import Translation

// A long-lived bridge to the system Translation framework, driven over NDJSON on stdin/stdout.
// The app starts one of these and keeps it: building a TranslationSession is the expensive part,
// so sessions are cached per language pair and strategy and reused for every later request.
// stdout carries protocol lines only — diagnostics go to stderr.

struct Request: Decodable {
    let id: Int
    let op: String
    /// Absent for `languages`, which asks about the system rather than about a pair.
    let from: String?
    let to: String?
    let quality: String?
    let texts: [String]?
}

struct Response: Encodable {
    let id: Int
    let ok: Bool
    var status: String?
    var texts: [String]?
    var languages: [String]?
    var error: String?
    var detail: String?
}

enum Failure: String {
    case packMissing        // pair is supported, but the user has not downloaded it
    case unsupported        // pair does not exist on this system
    case badRequest
    case failed
}

@available(macOS 26.4, *)
actor Sessions {
    private struct Key: Hashable {
        let from: String
        let to: String
        let highFidelity: Bool
    }

    private var cache: [Key: TranslationSession] = [:]

    func session(from: Locale.Language, to: Locale.Language, strategy: TranslationSession.Strategy) -> TranslationSession {
        let key = Key(
            from: from.maximalIdentifier,
            to: to.maximalIdentifier,
            highFidelity: strategy == .highFidelity
        )
        if let cached = cache[key] { return cached }
        let session = TranslationSession(installedSource: from, target: to, preferredStrategy: strategy)
        cache[key] = session
        return session
    }
}

@available(macOS 26.4, *)
func strategy(for quality: String?) -> TranslationSession.Strategy {
    // lowLatency is the default: roughly ten times faster, and good enough for reading rules.
    quality == "high" ? .highFidelity : .lowLatency
}

@available(macOS 26.4, *)
func availabilityStatus(
    from: Locale.Language,
    to: Locale.Language,
    strategy: TranslationSession.Strategy
) async -> LanguageAvailability.Status {
    await LanguageAvailability(preferredStrategy: strategy).status(from: from, to: to)
}

func emit(_ response: Response) {
    guard let data = try? JSONEncoder().encode(response) else { return }
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data([0x0a]))
}

func fail(_ id: Int, _ reason: Failure, _ detail: String? = nil) {
    emit(Response(id: id, ok: false, error: reason.rawValue, detail: detail))
}

@available(macOS 26.4, *)
func handle(_ request: Request, sessions: Sessions) async {
    let strategy = strategy(for: request.quality)

    if request.op == "languages" {
        // Reported by the system, so Settings never has to carry a hand-maintained list.
        let codes = await LanguageAvailability(preferredStrategy: strategy).supportedLanguages
            .compactMap { $0.languageCode?.identifier }
        emit(Response(id: request.id, ok: true, languages: Array(Set(codes)).sorted()))
        return
    }

    guard let from = request.from, let to = request.to else {
        fail(request.id, .badRequest, "\(request.op) needs from and to")
        return
    }
    let source = Locale.Language(identifier: from)
    let target = Locale.Language(identifier: to)
    let status = await availabilityStatus(from: source, to: target, strategy: strategy)

    if request.op == "status" {
        let name: String
        switch status {
        case .installed: name = "installed"
        case .supported: name = "supported"
        default: name = "unsupported"
        }
        emit(Response(id: request.id, ok: true, status: name))
        return
    }

    guard request.op == "translate" else {
        fail(request.id, .badRequest, "unknown op \(request.op)")
        return
    }
    guard let texts = request.texts else {
        fail(request.id, .badRequest, "translate needs texts")
        return
    }
    guard status == .installed else {
        // Downloading a pack needs UI (prepareTranslation), so a headless process can only report it.
        fail(request.id, status == .supported ? .packMissing : .unsupported)
        return
    }
    if texts.isEmpty {
        emit(Response(id: request.id, ok: true, texts: []))
        return
    }

    // Blank entries never reach the framework; they are stitched back in below.
    let indexed = texts.enumerated().filter { !$0.element.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    if indexed.isEmpty {
        emit(Response(id: request.id, ok: true, texts: texts))
        return
    }

    let session = await sessions.session(from: source, to: target, strategy: strategy)
    do {
        let requests = indexed.map {
            TranslationSession.Request(sourceText: $0.element, clientIdentifier: String($0.offset))
        }
        var translated = texts
        // Responses may arrive out of order, so each one is placed by its client identifier.
        for response in try await session.translations(from: requests) {
            guard let identifier = response.clientIdentifier, let slot = Int(identifier), texts.indices.contains(slot) else {
                continue
            }
            translated[slot] = response.targetText
        }
        emit(Response(id: request.id, ok: true, texts: translated))
    } catch {
        fail(request.id, .failed, String(describing: error))
    }
}

guard #available(macOS 26.4, *) else {
    FileHandle.standardError.write("rb-translate needs macOS 26.4 or newer\n".data(using: .utf8)!)
    exit(1)
}

let sessions = Sessions()
let decoder = JSONDecoder()

while let line = readLine(strippingNewline: true) {
    if line.isEmpty { continue }
    guard let data = line.data(using: .utf8), let request = try? decoder.decode(Request.self, from: data) else {
        FileHandle.standardError.write("skipped malformed line\n".data(using: .utf8)!)
        continue
    }
    await handle(request, sessions: sessions)
}

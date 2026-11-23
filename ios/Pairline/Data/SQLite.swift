import Foundation
import SQLite3

/// A minimal wrapper around the system SQLite library (no third-party code).
enum SQLValue: Equatable {
    case int(Int64)
    case double(Double)
    case text(String)
    case null

    static func from(_ value: Int64?) -> SQLValue { value.map(SQLValue.int) ?? .null }
    static func from(_ value: Int?) -> SQLValue { value.map { .int(Int64($0)) } ?? .null }
    static func from(_ value: String?) -> SQLValue { value.map(SQLValue.text) ?? .null }
    static func from(_ value: Bool) -> SQLValue { .int(value ? 1 : 0) }
}

struct SQLRow {
    fileprivate let values: [String: SQLValue]

    func int64(_ column: String) -> Int64? {
        switch values[column] {
        case .int(let v)?: return v
        case .double(let v)?: return Int64(v)
        case .text(let v)?: return Int64(v)
        default: return nil
        }
    }

    func int(_ column: String) -> Int? { int64(column).map { Int($0) } }

    func string(_ column: String) -> String? {
        switch values[column] {
        case .text(let v)?: return v
        case .int(let v)?: return String(v)
        case .double(let v)?: return String(v)
        default: return nil
        }
    }

    func bool(_ column: String) -> Bool { (int64(column) ?? 0) != 0 }
}

struct SQLiteError: Error, CustomStringConvertible {
    let code: Int32
    let message: String
    var description: String { "SQLite \(code): \(message)" }
}

final class SQLiteDatabase {
    private var handle: OpaquePointer?
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    /// `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION` from Apple's sqlite3.h.
    private static let fileProtectionUntilFirstAuth: Int32 = 0x0030_0000

    /// Opens the database at `url`, or an in-memory database when `url` is nil (demo mode and tests).
    init(url: URL?) throws {
        var flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if url != nil { flags |= Self.fileProtectionUntilFirstAuth }
        let path = url?.path ?? ":memory:"
        let status = sqlite3_open_v2(path, &handle, flags, nil)
        guard status == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(handle)
            throw SQLiteError(code: status, message: message)
        }
        if url != nil { try execute("PRAGMA journal_mode = WAL") }
        try execute("PRAGMA foreign_keys = ON")
    }

    deinit {
        sqlite3_close_v2(handle)
    }

    func execute(_ sql: String, _ arguments: [SQLValue] = []) throws {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        var status = sqlite3_step(statement)
        while status == SQLITE_ROW { status = sqlite3_step(statement) }
        guard status == SQLITE_DONE else { throw error(status) }
    }

    func query(_ sql: String, _ arguments: [SQLValue] = []) throws -> [SQLRow] {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        var rows: [SQLRow] = []
        let columns = sqlite3_column_count(statement)
        var status = sqlite3_step(statement)
        while status == SQLITE_ROW {
            var values: [String: SQLValue] = [:]
            for index in 0..<columns {
                let name = String(cString: sqlite3_column_name(statement, index))
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER: values[name] = .int(sqlite3_column_int64(statement, index))
                case SQLITE_FLOAT: values[name] = .double(sqlite3_column_double(statement, index))
                case SQLITE_TEXT: values[name] = .text(String(cString: sqlite3_column_text(statement, index)))
                default: values[name] = .null
                }
            }
            rows.append(SQLRow(values: values))
            status = sqlite3_step(statement)
        }
        guard status == SQLITE_DONE else { throw error(status) }
        return rows
    }

    func transaction(_ body: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do {
            try body()
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    var changes: Int { Int(sqlite3_changes(handle)) }

    private func prepare(_ sql: String, _ arguments: [SQLValue]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        let status = sqlite3_prepare_v2(handle, sql, -1, &statement, nil)
        guard status == SQLITE_OK else { throw error(status) }
        for (offset, argument) in arguments.enumerated() {
            let index = Int32(offset + 1)
            switch argument {
            case .int(let v): sqlite3_bind_int64(statement, index, v)
            case .double(let v): sqlite3_bind_double(statement, index, v)
            case .text(let v): sqlite3_bind_text(statement, index, v, -1, Self.transient)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
        return statement
    }

    private func error(_ status: Int32) -> SQLiteError {
        SQLiteError(code: status, message: handle.map { String(cString: sqlite3_errmsg($0)) } ?? "")
    }
}

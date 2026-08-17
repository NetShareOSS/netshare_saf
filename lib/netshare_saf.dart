import 'dart:io';

import 'package:flutter/services.dart';

/// A persisted Android Storage Access Framework directory grant.
class SafDirectory {
  /// The content URI for the selected tree.
  final String uri;

  /// The best display name Android exposes for the selected tree.
  final String name;

  /// Creates a persisted SAF directory model.
  const SafDirectory({required this.uri, required this.name});

  /// Creates a [SafDirectory] from platform channel data.
  factory SafDirectory.fromMap(Map<Object?, Object?> map) {
    return SafDirectory(
      uri: map['uri'] as String,
      name: (map['name'] as String?) ?? 'Selected folder',
    );
  }
}

/// A document exposed by an Android SAF directory.
class SafDocument {
  /// The content URI for this document.
  final String uri;

  /// The display name for this document.
  final String name;

  /// The Android MIME type for this document.
  final String mimeType;

  /// The document size in bytes when the provider reports it.
  final int? size;

  /// Whether this document is a directory.
  final bool isDirectory;

  /// Creates an SAF document model.
  const SafDocument({
    required this.uri,
    required this.name,
    required this.mimeType,
    this.size,
    required this.isDirectory,
  });

  /// Creates a [SafDocument] from platform channel data.
  factory SafDocument.fromMap(Map<Object?, Object?> map) {
    return SafDocument(
      uri: map['uri'] as String,
      name: (map['name'] as String?) ?? 'Untitled',
      mimeType: (map['mimeType'] as String?) ?? 'application/octet-stream',
      size: map['size'] as int?,
      isDirectory: (map['isDirectory'] as bool?) ?? false,
    );
  }
}

/// A write session opened against an SAF document.
class SafWriteSession {
  /// The native session id used for chunked writes.
  final String id;

  /// The content URI for the created document.
  final String uri;

  /// Creates a write session model.
  const SafWriteSession({required this.id, required this.uri});

  /// Creates a [SafWriteSession] from platform channel data.
  factory SafWriteSession.fromMap(Map<Object?, Object?> map) {
    return SafWriteSession(
      id: map['sessionId'] as String,
      uri: map['uri'] as String,
    );
  }
}

/// A read session opened against an SAF document.
class SafReadSession {
  /// The native session id used for chunked reads.
  final String id;

  /// Creates a read session model.
  const SafReadSession({required this.id});

  /// Creates a [SafReadSession] from platform channel data.
  factory SafReadSession.fromMap(Map<Object?, Object?> map) {
    return SafReadSession(id: map['sessionId'] as String);
  }
}

/// Android SAF operations used by NetShare hosting.
class NetshareSaf {
  static const MethodChannel _channel = MethodChannel('netshare_saf');

  /// Whether SAF is available for the current platform.
  static bool get isSupported => Platform.isAndroid;

  /// Opens the Android directory picker and persists read/write access.
  static Future<SafDirectory?> pickDirectory() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'pickDirectory',
    );
    if (result == null) return null;
    return SafDirectory.fromMap(result);
  }

  /// Opens the Android file picker without copying bytes into cache.
  ///
  /// Returns `null` if the user cancels. Each document has persistable
  /// read-only URI permission until [releasePersistableUriPermission].
  static Future<List<SafDocument>?> pickFiles({
    bool allowMultiple = true,
  }) async {
    final result = await _channel.invokeListMethod<Object?>('pickFiles', {
      'allowMultiple': allowMultiple,
    });
    if (result == null) return null;
    return result
        .whereType<Map<Object?, Object?>>()
        .map(SafDocument.fromMap)
        .toList();
  }

  /// Drops persistable read access for a previously picked [documentUri].
  static Future<void> releasePersistableUriPermission(String documentUri) {
    return _channel.invokeMethod<void>('releasePersistableUriPermission', {
      'documentUri': documentUri,
    });
  }

  /// Returns whether [treeUri] still has a persisted permission grant.
  static Future<bool> hasPersistedPermission(String treeUri) async {
    return await _channel.invokeMethod<bool>('hasPersistedPermission', {
          'treeUri': treeUri,
        }) ??
        false;
  }

  /// Lists direct child documents inside [treeUri].
  static Future<List<SafDocument>> listFiles(String treeUri) async {
    final result = await _channel.invokeListMethod<Object?>('listFiles', {
      'treeUri': treeUri,
    });
    return (result ?? const <Object?>[])
        .whereType<Map<Object?, Object?>>()
        .map(SafDocument.fromMap)
        .toList();
  }

  /// Reads all bytes from [documentUri].
  static Future<Uint8List> readFile(String documentUri) async {
    final result = await _channel.invokeMethod<Uint8List>('readFile', {
      'documentUri': documentUri,
    });
    return result ?? Uint8List(0);
  }

  /// Opens [documentUri] for chunked reads, optionally seeking to [offset].
  static Future<SafReadSession> startReadFile(
    String documentUri, {
    int offset = 0,
  }) async {
    if (offset < 0) {
      throw ArgumentError.value(offset, 'offset', 'must be >= 0');
    }
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'startReadFile',
      {'documentUri': documentUri, 'offset': offset},
    );
    if (result == null) {
      throw StateError('Could not start SAF read session.');
    }
    return SafReadSession.fromMap(result);
  }

  /// Reads at most [chunkSize] bytes from an open SAF read [sessionId].
  static Future<Uint8List> readFileChunk(
    String sessionId, {
    int chunkSize = 262144,
  }) async {
    final result = await _channel.invokeMethod<Uint8List>('readFileChunk', {
      'sessionId': sessionId,
      'chunkSize': chunkSize,
    });
    return result ?? Uint8List(0);
  }

  /// Closes an open SAF read [sessionId].
  static Future<void> finishReadFile(String sessionId) {
    return _channel.invokeMethod<void>('finishReadFile', {
      'sessionId': sessionId,
    });
  }

  /// Streams [documentUri] through repeated SAF read chunks.
  ///
  /// When [offset] is non-zero the native side seeks before the first chunk.
  /// When [length] is set, at most that many bytes are yielded.
  static Stream<Uint8List> readFileStream(
    String documentUri, {
    int offset = 0,
    int? length,
  }) async* {
    if (length != null && length < 0) {
      throw ArgumentError.value(length, 'length', 'must be >= 0');
    }
    final session = await startReadFile(documentUri, offset: offset);
    var remaining = length;
    try {
      while (remaining == null || remaining > 0) {
        final chunkSize = remaining == null
            ? 262144
            : remaining > 262144
                ? 262144
                : remaining;
        final chunk = await readFileChunk(session.id, chunkSize: chunkSize);
        if (chunk.isEmpty) break;
        if (remaining != null) {
          remaining -= chunk.length;
        }
        yield chunk;
      }
    } finally {
      await finishReadFile(session.id);
    }
  }

  /// Opens an Android viewer for [documentUri].
  static Future<void> openFile(String documentUri, String mimeType) {
    return _channel.invokeMethod<void>('openFile', {
      'documentUri': documentUri,
      'mimeType': mimeType,
    });
  }

  /// Starts a chunked write of [fileName] inside [treeUri].
  static Future<SafWriteSession> startWriteFile({
    required String treeUri,
    required String fileName,
    required String mimeType,
  }) async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'startWriteFile',
      {'treeUri': treeUri, 'fileName': fileName, 'mimeType': mimeType},
    );
    if (result == null) {
      throw StateError('Could not start SAF write session.');
    }
    return SafWriteSession.fromMap(result);
  }

  /// Writes [bytes] to an open SAF write [sessionId].
  static Future<void> writeFileChunk(String sessionId, Uint8List bytes) {
    return _channel.invokeMethod<void>('writeFileChunk', {
      'sessionId': sessionId,
      'bytes': bytes,
    });
  }

  /// Flushes and closes an open SAF write [sessionId].
  static Future<void> finishWriteFile(String sessionId) {
    return _channel.invokeMethod<void>('finishWriteFile', {
      'sessionId': sessionId,
    });
  }

  /// Cancels and closes an open SAF write [sessionId].
  static Future<void> abortWriteFile(String sessionId) {
    return _channel.invokeMethod<void>('abortWriteFile', {
      'sessionId': sessionId,
    });
  }
}

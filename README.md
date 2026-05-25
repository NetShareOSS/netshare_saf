# netshare_saf

`netshare_saf` is a small Flutter plugin that exposes the Android Storage Access Framework (SAF) APIs needed by NetShare-style local file hosting apps.

It is intentionally narrow. The package is not a general file manager UI toolkit. It focuses on the pieces an app needs when it must serve, receive, or process arbitrary user-selected files on Android 11+ without requesting `MANAGE_EXTERNAL_STORAGE`.

## Why this exists

Android scoped storage prevents apps from treating shared storage as a normal filesystem. APIs such as `dart:io Directory.list()` and raw file paths may only expose media files, or may fail completely, when the selected folder contains arbitrary documents.

For a local HTTP file-sharing app, that is a problem. The host needs to:

- Let the user pick a folder through Android's system picker.
- Persist read/write permission to that folder.
- List direct child documents in that folder.
- Stream file bytes to clients without copying the whole file first.
- Write incoming upload chunks directly into the selected folder.

Existing SAF packages are useful for broad file-management workflows, but NetShare needed a compact bridge with an exact server-facing contract: pick, persist, list, stream reads, and chunked writes. This package provides that small surface area.

## Features

- Android `ACTION_OPEN_DOCUMENT_TREE` directory picker.
- Persisted tree URI permissions.
- Permission validation for previously selected folders.
- Direct child document listing with name, URI, MIME type, size, and directory flag.
- Whole-file reads for small utility operations.
- Chunked read sessions and `Stream<Uint8List>` file reads for serving large files.
- Chunked write sessions for uploads.
- Android viewer handoff for SAF document URIs.

## Platform Support

| Platform | Support |
| --- | --- |
| Android | Supported |
| iOS | Not supported |
| macOS | Not supported |
| Windows | Not supported |
| Linux | Not supported |
| Web | Not supported |

Use `NetshareSaf.isSupported` before calling the API.

## Installation

Add the package from GitHub:

```yaml
dependencies:
  netshare_saf:
    git:
      url: git@github.com:NetShareOSS/netshare_saf.git
```

No storage permissions are required in `AndroidManifest.xml` for SAF folder access. The user grants access through the Android system picker.

## Basic Usage

Pick a directory and persist access:

```dart
final directory = await NetshareSaf.pickDirectory();
if (directory == null) {
  return;
}

final hasPermission = await NetshareSaf.hasPersistedPermission(directory.uri);
```

List direct child files:

```dart
final documents = await NetshareSaf.listFiles(directory.uri);
final files = documents.where((document) => !document.isDirectory);
```

Stream a document:

```dart
final stream = NetshareSaf.readFileStream(document.uri);
await for (final chunk in stream) {
  // Send chunk to an HTTP response, socket, hash sink, etc.
}
```

Write a file in chunks:

```dart
final session = await NetshareSaf.startWriteFile(
  treeUri: directory.uri,
  fileName: 'example.txt',
  mimeType: 'text/plain',
);

try {
  await NetshareSaf.writeFileChunk(session.id, Uint8List.fromList([1, 2, 3]));
  await NetshareSaf.finishWriteFile(session.id);
} catch (_) {
  await NetshareSaf.abortWriteFile(session.id);
  rethrow;
}
```

Open a document in another Android app:

```dart
await NetshareSaf.openFile(document.uri, document.mimeType);
```

## HTTP Server Example

The package is designed to work well with a `shelf` server:

```dart
Future<Response> fileHandler(String fileName) async {
  final documents = await NetshareSaf.listFiles(directory.uri);
  final document = documents.firstWhere((item) => item.name == fileName);

  final headers = <String, String>{
    'content-type': document.mimeType,
  };
  if (document.size != null) {
    headers['content-length'] = document.size.toString();
  }

  return Response.ok(
    NetshareSaf.readFileStream(document.uri),
    headers: headers,
  );
}
```

For uploads, parse the multipart request in Dart and forward each incoming chunk to `writeFileChunk`.

## API Overview

### Models

- `SafDirectory`: selected tree URI and display name.
- `SafDocument`: document URI, display name, MIME type, optional size, and directory flag.
- `SafReadSession`: native session id for chunked reads.
- `SafWriteSession`: native session id and created document URI for chunked writes.

### Methods

- `NetshareSaf.pickDirectory()`
- `NetshareSaf.hasPersistedPermission(treeUri)`
- `NetshareSaf.listFiles(treeUri)`
- `NetshareSaf.readFile(documentUri)`
- `NetshareSaf.startReadFile(documentUri)`
- `NetshareSaf.readFileChunk(sessionId, chunkSize: 262144)`
- `NetshareSaf.finishReadFile(sessionId)`
- `NetshareSaf.readFileStream(documentUri)`
- `NetshareSaf.openFile(documentUri, mimeType)`
- `NetshareSaf.startWriteFile(treeUri, fileName, mimeType)`
- `NetshareSaf.writeFileChunk(sessionId, bytes)`
- `NetshareSaf.finishWriteFile(sessionId)`
- `NetshareSaf.abortWriteFile(sessionId)`

## Design Notes

This package deliberately avoids returning fake filesystem paths for SAF documents. A SAF URI is not a stable raw path, and treating it like one is the usual source of Android 11+ storage bugs.

The API keeps Android document access explicit:

- Store the tree URI if you want to remember a folder.
- Check persisted permission before using a stored URI.
- Use document URIs for reads and opens.
- Use write sessions for creating or replacing files.

## Limitations

- Only direct children of the selected directory are listed.
- Recursive traversal is not implemented yet.
- Random-access reads and HTTP range requests are not implemented yet.
- Write sessions replace an existing file with the same display name.
- The plugin currently targets Android only.

## License

TBD.

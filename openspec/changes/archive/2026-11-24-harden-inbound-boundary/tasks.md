# Tasks

## 1. Path decisions

- [x] 1.1 Add unit tests for `/%61dmin/...`, `/admin;x/...`, `/api/v1/%69ntegrations/<slug>` and `/api/v1/integrations;x/<slug>` with a key set; verify they fail on the current code (`InboundKeyTest`: `/%61dmin/api/integrations` answered 200 without a key)
- [x] 1.2 Give the key filter and the gate one shared helper that returns the normalised path (`web/RequestPaths`, Spring's `UrlPathHelper` with decoding and `;` removal). Make the key filter deny by default; open without the key are only GET/HEAD of `/`, `/index.html`, `/favicon.svg`, `/assets/...` and the OpenAPI documents, and never a path with a `.` or `..` segment. Verify that 1.1 passes and `mvn test` is green

## 2. Imported contracts

- [x] 2.1 Changed during the build: swagger-parser ignores `setSafelyResolveURL` and the allowlist for Swagger 2.0 (a probe read a local file and fetched a URL with them set), so `OpenApiImporter` checks every `$ref` itself before parsing. Allowed are `#...` references and, for a URL import, absolute references to the contract's own origin; anything else refuses the import naming the references. For OpenAPI 3 the parser's safe resolution is also on, with an empty allowlist rather than null (null made a refused file reference throw instead of warn). Verified with `ContractImporterTest.aReferenceToALocalFileIsRefusedAndNotRead` (Swagger 2.0 and OpenAPI 3) and `aUrlImportMayReferOnlyToItsOwnOrigin`
- [x] 2.2 Read contract, WSDL and XSD fetches through a stream capped at `MAX_ZIP_BYTES`, and cap a WSDL import's schemas at `MAX_ZIP_ENTRIES` files and `MAX_ZIP_BYTES` in all. Verified with `aContractUrlThatAnswersTooMuchIsRefused` (a chunked answer of up to 64 MB) and `aWsdlCanFetchOnlySoManySchemas`

## 3. Outbound bodies

- [x] 3.1 `XmlFormat.write` and `SoapEnvelope.write` refuse a key that is not an XML element name (`XmlFormat.BadNameException`); on the request side it becomes 400 before any call. The rule is a letter or `_` first, then letters, digits, `_ - .`, with letters in the Unicode sense, so keys such as `märkus` stay valid (the ASCII-only `XmlFormat.NAME` would have refused them). Verified with `XmlIntegrationTest.aKeyThatIsNoXmlNameIsRefusedWithoutACall` (200 with the injected elements sent, without the check) and `SoapEnvelopeTest.aKeyThatIsNoXmlNameIsRefused`
- [x] 3.2 Refuse control characters in a file's `contentType` and `fileName` in `Blobs.check`, and refuse them in `Multipart.write` as a last guard. Verified with `BlobsTest.lineBreaksInTheFilesTypeOrNameAreRefused`; the raw-binary path takes the type from `Blobs.check`'d input or from an HTTP header, which can't hold a line break

## 4. Body size

- [x] 4.1 The gate reads every body of an integration call itself, up to its limit plus one byte, before the call is admitted: files `mapper.max-blob-bytes` (raw) or files × that + 1 MiB (multipart), anything else the new `mapper.max-body-bytes` (default 10 MiB) plus room for the Input's files as base64. Over the limit is 413 in the existing error shape; otherwise the route reads the buffered body. Verified with `VersionLifecycleTest.aJsonBodyOverTheLimitIs413EvenWhenChunked` (200 on the old code)

## 5. Docs and end-to-end

- [x] 5.1 `README.md` (root, where the variables are): `INBOUND_API_KEY`'s open paths, `MAPPER_MAXBLOBBYTES` and the new `MAPPER_MAXBODYBYTES`, and the `$ref` rule under "Security in short"
- [x] 5.2 PathchbayTestAndMock: `ErrorHandlingTest.aPathParameterDoesNotSkipTheGate`, `aChunkedJsonBodyOverTheLimitIs413`, `aContractWithAnExternalReferenceIsRefused`, `DocumentsTest.aLineBreakInAJsonBlobsTypeIs400`; all 208 tests pass. The key bypass itself has no end-to-end test, because the test Patchbay runs without `INBOUND_API_KEY` (setup would need the key); `InboundKeyTest` covers it against a real server. The XML name injection is covered by `XmlIntegrationTest` only, since an end-to-end case needs a new XML integration with a whole-object link

Engine: `mvn test` 228 tests pass.

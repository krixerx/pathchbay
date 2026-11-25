package integrationmapper.engine.contract;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.format.SafeXml;
import integrationmapper.engine.model.Contract;
import integrationmapper.engine.model.OpenApiContract;
import integrationmapper.engine.model.WsdlContract;

/**
 * Imports a contract by URL, as text or as a ZIP (spec FR-001, FR-004): fetches it, tells an OpenAPI 3 or Swagger 2.0
 * file ({@link OpenApiImporter}) from a WSDL 1.1 one ({@link WsdlImporter}), and stores the normalized contract next to
 * its original files, which are kept unchanged.
 */
@Component
public class ContractImporter {

    private final ConfigStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** For the schemas a WSDL imports (D-30): no redirects, so a fetch can't leave the contract's host. */
    private final HttpClient noRedirects = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ContractImporter(ConfigStore store) {
        this.store = store;
    }

    /** Fetches a contract by URL from the engine. An unreachable URL gets the D-5 message: upload the file instead. */
    public String fetch(String url) {
        return fetch(url, http);
    }

    /** Fetches a schema a WSDL imports; a redirect is refused, since it could point to another host. */
    String fetchSchema(String url) {
        return fetch(url, noRedirects);
    }

    private String fetch(String url, HttpClient client) {
        try {
            HttpResponse<InputStream> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                if (r.statusCode() >= 300 && r.statusCode() < 400) {
                    throw new IllegalArgumentException("The URL answered " + r.statusCode() + " (a redirect), which is not followed for schemas");
                }
                if (r.statusCode() >= 400) {
                    throw new IllegalArgumentException("The URL answered " + r.statusCode() + ". Check the address, or download the file and upload it instead.");
                }
                // Read no more than the limit of a ZIP import: a contract larger than that is refused, not read whole.
                byte[] bytes = body.readNBytes((int) MAX_ZIP_BYTES + 1);
                if (bytes.length > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("The URL answered more than " + MAX_ZIP_BYTES / 1024 / 1024 + " MB, the limit for a contract");
                }
                return new String(bytes, charset(r));
            }
        } catch (ConnectException | UnknownHostException | HttpTimeoutException e) {
            throw new IllegalArgumentException("The engine could not reach " + url + " (" + e.getClass().getSimpleName()
                    + "). If the file is only reachable from your computer, download it and upload the file instead.");
        } catch (IllegalArgumentException e) {
            throw e.getMessage() != null && e.getMessage().startsWith("The URL") ? e
                    : new IllegalArgumentException("Not a valid URL: " + url);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + url + ": " + e.getMessage()
                    + ". Download the file and upload it instead.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses, normalizes and stores the contract under {@code id}; a re-import replaces the previous one.
     * {@code sourceUrl}: where the text was fetched from, null for an upload.
     */
    public Contract importContract(String id, String name, String text, String sourceUrl) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The contract is empty");
        }
        if (WsdlImporter.looksLikeWsdl(text)) {
            SafeXml.Sources sources = sourceUrl == null ? SafeXml.Sources.none() : SafeXml.Sources.ofUrl(sourceUrl, limited(this::fetchSchema));
            return importWsdl(id, name, sourceUrl == null ? "service.wsdl" : sourceUrl, text.getBytes(StandardCharsets.UTF_8), sources,
                    sourceUrl);
        }
        if (text.stripLeading().startsWith("<")) {
            throw new IllegalArgumentException(NOT_A_CONTRACT);
        }
        OpenApiImporter.Result r = OpenApiImporter.read(text, sourceUrl);

        boolean json = text.stripLeading().startsWith("{");
        String sourceFile = id + ".openapi." + (json ? "json" : "yaml");
        removeSources(id);
        store.write(store.root().resolve("contracts").resolve(sourceFile), out -> out.write(text.getBytes(StandardCharsets.UTF_8)));
        Contract contract = new OpenApiContract(id, name, sourceFile, sourceUrl, OffsetDateTime.now(ZoneOffset.UTC).toString(), r.servers(),
                r.operations(), r.warnings());
        store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), contract);
        return contract;
    }

    /**
     * The schemas one WSDL import may fetch: as many files and bytes in all as a ZIP import may hold, so a WSDL that
     * imports schemas without end stops instead of filling the memory.
     */
    static Function<String, String> limited(Function<String, String> fetch) {
        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        return url -> {
            if (files.incrementAndGet() > MAX_ZIP_ENTRIES) {
                throw new IllegalArgumentException("The WSDL imports more than " + MAX_ZIP_ENTRIES + " schema files");
            }
            String text = fetch.apply(url);
            if (bytes.addAndGet(text.length()) > MAX_ZIP_BYTES) {
                throw new IllegalArgumentException("The WSDL's schemas hold more than " + MAX_ZIP_BYTES / 1024 / 1024 + " MB");
            }
            return text;
        };
    }

    private static Charset charset(HttpResponse<?> r) {
        String type = r.headers().firstValue("Content-Type").orElse("");
        int at = type.toLowerCase(java.util.Locale.ROOT).indexOf("charset=");
        if (at >= 0) {
            try {
                return Charset.forName(type.substring(at + 8).split(";")[0].trim().replace("\"", ""));
            } catch (RuntimeException e) {
                // an unknown charset name: read it as UTF-8 like a file without one
            }
        }
        return StandardCharsets.UTF_8;
    }

    static final String NOT_A_CONTRACT = "Not a contract Patchbay can read: give an OpenAPI 3, Swagger 2.0 or WSDL 1.1 file";
    static final int MAX_ZIP_ENTRIES = 500;
    public static final long MAX_ZIP_BYTES = 20L * 1024 * 1024;

    /**
     * Imports a WSDL contract from a ZIP holding the WSDL and the XSD files it imports (D-30). The WSDL is the one
     * {@code .wsdl} file nearest the top of the ZIP.
     */
    public Contract importZip(String id, String name, byte[] zip) throws IOException {
        Map<String, byte[]> entries = unzip(zip);
        int best = Integer.MAX_VALUE;
        List<String> candidates = new ArrayList<>();
        for (String path : entries.keySet()) {
            if (path.toLowerCase().endsWith(".wsdl")) {
                int depth = path.split("/").length;
                if (depth < best) {
                    best = depth;
                    candidates.clear();
                }
                if (depth == best) {
                    candidates.add(path);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("The ZIP has no .wsdl file");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("The ZIP has several WSDL files at the same level (" + String.join(", ", candidates)
                    + "); keep only the service's main WSDL there");
        }
        String main = candidates.get(0);
        return importWsdl(id, name, main, entries.get(main), SafeXml.Sources.ofZip(entries), null);
    }

    /** The files of a ZIP by path; refuses absolute paths, {@code ..}, and more than the size limits. */
    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) {
                    continue;
                }
                String path = e.getName().replace('\\', '/');
                if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || List.of(path.split("/")).contains("..")) {
                    throw new IllegalArgumentException("The ZIP entry " + e.getName() + " has a path outside the ZIP");
                }
                if (out.size() >= MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("The ZIP has more than " + MAX_ZIP_ENTRIES + " files");
                }
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) {
                        throw new IllegalArgumentException("The ZIP holds more than " + MAX_ZIP_BYTES / 1024 / 1024 + " MB");
                    }
                    bytes.write(buf, 0, n);
                }
                out.put(path, bytes.toByteArray());
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Not a valid ZIP: " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The ZIP is empty or not a ZIP");
        }
        return out;
    }

    /**
     * Reads the WSDL, then stores every file it used in {@code contracts/<id>.wsdl/} (replacing what was there) and
     * the normalized contract. {@code mainKey}: the WSDL's path in the ZIP or its URL.
     */
    private Contract importWsdl(String id, String name, String mainKey, byte[] main, SafeXml.Sources sources, String sourceUrl)
            throws IOException {
        WsdlImporter.Result r = WsdlImporter.read(mainKey, main, sources);
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, byte[]> stored = new LinkedHashMap<>();
        String sourceFile = null;
        for (Map.Entry<String, byte[]> e : sources.used().entrySet()) {
            String file = sources.isZip() ? e.getKey() : uniqueName(fileName(e.getKey()), stored.keySet());
            stored.put(file, e.getValue());
            files.put(file, e.getKey());
            if (e.getKey().equals(mainKey)) {
                sourceFile = id + WSDL_DIR + "/" + file;
            }
        }
        removeSources(id);
        java.nio.file.Path dir = store.root().resolve("contracts").resolve(id + WSDL_DIR);
        for (Map.Entry<String, byte[]> e : stored.entrySet()) {
            java.nio.file.Path target = dir.resolve(e.getKey()).normalize();
            if (!target.startsWith(dir)) {
                throw new IllegalArgumentException("The file " + e.getKey() + " has a path outside the contract");
            }
            java.nio.file.Files.createDirectories(target.getParent());
            store.write(target, out -> out.write(e.getValue()));
        }
        Contract contract = new WsdlContract(id, name, sourceFile, sourceUrl, OffsetDateTime.now(ZoneOffset.UTC).toString(), r.servers(),
                r.operations(), r.warnings(), files);
        store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), contract);
        return contract;
    }

    /** The folder suffix of a WSDL contract's original files. */
    public static final String WSDL_DIR = ".wsdl";

    /** Removes the original files of contract {@code id}: {@code <id>.openapi.*} and the {@code <id>.wsdl/} folder. */
    public void removeSources(String id) throws IOException {
        java.nio.file.Path dir = store.root().resolve("contracts");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(id + ".openapi.")) {
                    java.nio.file.Files.delete(p);
                } else if (n.equals(id + WSDL_DIR) && java.nio.file.Files.isDirectory(p)) {
                    deleteTree(p);
                }
            }
        }
    }

    public static void deleteTree(java.nio.file.Path dir) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(p);
            }
        }
    }

    /** A file name for a fetched URL: its last path segment plus its query, e.g. {@code person_xsd=1.xsd}. */
    static String fileName(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "file.xml";
        }
        String path = u.getPath() == null ? "" : u.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String query = u.getQuery();
        String n = (last.isEmpty() ? "service" : last) + (query == null ? "" : "_" + query);
        n = n.replaceAll("[^A-Za-z0-9._=-]", "_");
        if (query != null) {
            n += query.toLowerCase().startsWith("wsdl") ? ".wsdl" : ".xsd";
        }
        return n;
    }

    private static String uniqueName(String base, java.util.Set<String> taken) {
        String n = base;
        for (int i = 2; taken.contains(n); i++) {
            int dot = base.lastIndexOf('.');
            n = dot < 0 ? base + "-" + i : base.substring(0, dot) + "-" + i + base.substring(dot);
        }
        return n;
    }
}

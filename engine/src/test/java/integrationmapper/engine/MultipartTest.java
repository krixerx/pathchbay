package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/** D-29: multipart/form-data read and written by Patchbay itself, byte for byte. */
class MultipartTest {

    /** Every byte value, plus a CRLF and something that looks like a boundary, inside a file. */
    static final byte[] TRICKY;

    static {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 256; i++) {
            out.write(i);
        }
        out.writeBytes("\r\n--not-the-boundary\r\n".getBytes(StandardCharsets.US_ASCII));
        TRICKY = out.toByteArray();
    }

    static byte[] body(String boundary, Object... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object c : chunks) {
            out.writeBytes(c instanceof byte[] b ? b : c.toString().replace("BOUNDARY", boundary).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void partsAreReadWithTheirNamesFileNamesTypesAndExactBytes() {
        byte[] body = body("xYz", "preamble\r\n--BOUNDARY\r\n",
                "Content-Disposition: form-data; name=\"title\"\r\n\r\nContract\r\n--BOUNDARY\r\n",
                "Content-Disposition: form-data; name=\"file\"; filename=\"a \\\"b\\\".pdf\"\r\nContent-Type: application/pdf\r\n\r\n", TRICKY,
                "\r\n--BOUNDARY\r\n",
                "Content-Disposition: form-data; name=\"other\"; filename*=UTF-8''r%C3%A4%C3%A4k.txt\r\n\r\n\r\n--BOUNDARY--\r\n");

        List<Multipart.Part> parts = Multipart.read(body, "multipart/form-data; boundary=\"xYz\"");

        assertThat(parts).extracting(Multipart.Part::name).containsExactly("title", "file", "other");
        assertThat(new String(parts.get(0).bytes(), StandardCharsets.UTF_8)).isEqualTo("Contract");
        assertThat(parts.get(0).fileName()).isNull();
        assertThat(parts.get(1).fileName()).isEqualTo("a \"b\".pdf");
        assertThat(parts.get(1).contentType()).isEqualTo("application/pdf");
        assertThat(parts.get(1).bytes()).isEqualTo(TRICKY);
        assertThat(parts.get(2).fileName()).isEqualTo("rääk.txt");
        assertThat(parts.get(2).bytes()).isEmpty();
    }

    @Test
    void aBodyThatIsNotMultipartIsRefused() {
        assertThatThrownBy(() -> Multipart.read("hello".getBytes(), "multipart/form-data; boundary=b"))
                .hasMessageContaining("The multipart body could not be read");
        assertThatThrownBy(() -> Multipart.read(body("b", "--BOUNDARY\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nno end"),
                "multipart/form-data; boundary=b")).hasMessageContaining("The multipart body could not be read");
        assertThatThrownBy(() -> Multipart.read(new byte[0], "multipart/form-data"))
                .hasMessageContaining("no boundary");
    }

    @Test
    void writtenPartsReadBackTheSame() {
        ObjectNode mapped = Json.MAPPER.createObjectNode();
        mapped.put("title", "Contract").put("pages", 3).putNull("skipped");
        mapped.putObject("file").put("fileName", "rääk.pdf").put("contentType", "application/pdf")
                .put("data", java.util.Base64.getEncoder().encodeToString(TRICKY));

        Multipart.Body written = Multipart.write(mapped);
        assertThat(written.contentType()).startsWith("multipart/form-data; boundary=");
        List<Multipart.Part> parts = Multipart.read(written.bytes(), written.contentType());

        assertThat(parts).extracting(Multipart.Part::name).containsExactly("title", "pages", "file");
        assertThat(new String(parts.get(1).bytes(), StandardCharsets.UTF_8)).isEqualTo("3");
        assertThat(parts.get(2).fileName()).isEqualTo("rääk.pdf");
        assertThat(parts.get(2).contentType()).isEqualTo("application/pdf");
        assertThat(parts.get(2).bytes()).isEqualTo(TRICKY);
        assertThat(new String(written.bytes(), StandardCharsets.UTF_8))
                .contains("Content-Disposition: form-data; name=\"file\"; filename=\"r__k.pdf\"; filename*=UTF-8''r%C3%A4%C3%A4k.pdf");
    }
}

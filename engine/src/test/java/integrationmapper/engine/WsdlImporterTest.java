package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WSDL 1.1 import into SOAP operations and field trees (D-30, spec wsdl-contract-import, FR-002, FR-004). */
class WsdlImporterTest {

    @TempDir
    Path dir;

    ContractImporter importer() {
        return new ContractImporter(new ConfigStore(dir.toString()));
    }

    static String resource(String path) throws Exception {
        try (var in = WsdlImporterTest.class.getResourceAsStream("/wsdl/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] personZip() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("person.wsdl", resource("person/person.wsdl"));
        files.put("schemas/common.xsd", resource("person/schemas/common.xsd"));
        return zip(files);
    }

    static Contract.Operation op(Contract c, String id) {
        return c.operations().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    static PayloadDefinition.Field field(PayloadDefinition.Field parent, String name) {
        return parent.children().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everySoapPortListsItsOperationsWithPortAndOperationAsId() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());

        assertThat(c.sourceKind()).isEqualTo("wsdl");
        assertThat(c.operations()).extracting(Contract.Operation::id).containsExactly(
                "PersonSoap/GetPerson", "PersonSoap/SavePerson", "PersonSoap12/GetPerson", "PersonSoap12/SavePerson",
                "PersonRpc/GetPerson", "PersonRpc/SavePerson");
        Contract.Operation get11 = op(c, "PersonSoap/GetPerson");
        Contract.Operation get12 = op(c, "PersonSoap12/GetPerson");
        assertThat(get11.protocol()).isEqualTo("soap");
        assertThat(get11.method()).isEqualTo("POST");
        assertThat(get11.summary()).isEqualTo("Finds a person by personal code");
        assertThat(get11.unsupported()).isNull();
        assertThat(get11.soap().version()).isEqualTo("1.1");
        assertThat(get12.soap().version()).isEqualTo("1.2");
        assertThat(get11.soap().action()).isEqualTo("urn:GetPerson");
        assertThat(get11.soap().body()).isEqualTo(new SoapTarget.Element("GetPerson", "urn:example:person"));
        assertThat(get11.soap().address()).isEqualTo("http://registry.example/ws/person");
        assertThat(get11.soap().response().element()).isEqualTo("GetPersonResponse");
    }

    @Test
    void portsWithDifferentAddressesGetAWarning() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());

        assertThat(c.servers()).containsExactly("http://registry.example/ws/person", "http://registry.example/ws/person-rpc");
        assertThat(c.warnings()).anyMatch(w -> w.contains("different addresses") && w.contains("one connection"));
        assertThat(op(c, "PersonRpc/GetPerson").soap().address()).isEqualTo("http://registry.example/ws/person-rpc");
    }

    @Test
    void rpcAndEncodedOperationsAreListedButUnsupported() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());

        assertThat(op(c, "PersonRpc/GetPerson").unsupported()).contains("RPC style");
        assertThat(op(c, "PersonRpc/SavePerson").unsupported()).contains("RPC style");
        assertThat(op(c, "PersonRpc/GetPerson").requestBody()).isNull();
    }

    @Test
    void xsdTypesBecomeFieldTypes() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        PayloadDefinition.Field person = field(op(c, "PersonSoap/GetPerson").response(), "person");

        assertThat(person.type()).isEqualTo("object");
        assertThat(person.required()).isFalse();
        assertThat(field(person, "firstName").type()).isEqualTo("text");
        assertThat(field(person, "firstName").required()).isTrue();
        assertThat(field(person, "birthDate").type()).isEqualTo("date");
        assertThat(field(person, "height").type()).isEqualTo("number");
        assertThat(field(person, "height").required()).isFalse();
        assertThat(field(person, "children").type()).isEqualTo("integer");
        assertThat(field(person, "updated").type()).isEqualTo("datetime");
        assertThat(field(person, "photo").type()).isEqualTo("text");
        assertThat(field(person, "photo").format()).isEqualTo("byte");
        assertThat(field(person, "nickname").required()).isFalse(); // nillable
        assertThat(field(person, "status").type()).isEqualTo("integer"); // restriction of xs:int
        PayloadDefinition.Field address = field(person, "address");
        assertThat(address.children()).extracting(PayloadDefinition.Field::name).containsExactly("street", "city", "postalCode");
        PayloadDefinition.Field request = op(c, "PersonSoap/GetPerson").requestBody();
        assertThat(request.name()).isEqualTo("body");
        assertThat(request.children()).extracting(PayloadDefinition.Field::name).containsExactly("personCode", "includeHistory");
        assertThat(field(request, "includeHistory").type()).isEqualTo("boolean");
    }

    @Test
    void repeatedElementsBecomeListsAndTheirPathsAreKept() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        Contract.Operation get = op(c, "PersonSoap/GetPerson");
        PayloadDefinition.Field person = field(get.response(), "person");

        assertThat(field(person, "phones").type()).isEqualTo("list");
        assertThat(field(person, "phones").items().type()).isEqualTo("text");
        PayloadDefinition.Field relatives = field(person, "relatives");
        assertThat(relatives.type()).isEqualTo("list");
        assertThat(relatives.items().children()).extracting(PayloadDefinition.Field::name).containsExactly("name", "phones");
        assertThat(get.soap().response().lists()).containsExactly("person.phones[]", "person.relatives[]", "person.relatives[].phones[]");
    }

    @Test
    void aTypeThatContainsItselfIsShownWithoutInnerFields() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        PayloadDefinition.Field manager = field(field(op(c, "PersonSoap/GetPerson").response(), "person"), "manager");

        assertThat(manager.type()).isEqualTo("object");
        assertThat(manager.children()).isEmpty();
        assertThat(c.warnings()).anyMatch(w -> w.contains("manager refers to itself"));
    }

    @Test
    void choiceTakesTheFirstOptionAndAnyIsLeftOutWithWarnings() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        PayloadDefinition.Field body = op(c, "PersonSoap/SavePerson").requestBody();

        assertThat(field(body, "contact").children()).extracting(PayloadDefinition.Field::name).containsExactly("email");
        assertThat(body.children()).extracting(PayloadDefinition.Field::name).containsExactly("firstName", "lastName", "address", "contact");
        assertThat(c.warnings()).anyMatch(w -> w.contains("choice of 2 options"));
        assertThat(c.warnings()).anyMatch(w -> w.contains("xs:any"));
    }

    @Test
    void requestNamespacesAndOrderAreRecordedPerElementPath() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        SoapTarget save = op(c, "PersonSoap/SavePerson").soap();

        assertThat(save.requestOrder().get("body")).containsExactly("firstName", "lastName", "address", "contact");
        assertThat(save.requestOrder().get("body.address")).containsExactly("street", "city", "postalCode");
        // address itself is a local element of the person schema; its children come from the common schema.
        assertThat(save.requestNs()).doesNotContainKey("body.address")
                .containsEntry("body.address.street", "urn:example:common")
                .containsEntry("body.address.city", "urn:example:common");
    }

    @Test
    void unqualifiedLocalElementsHaveNoNamespaceAndBaseTypesComeFirst() throws Exception {
        Contract c = importer().importContract("orders", "Orders", resource("unqualified.wsdl"), null);
        Contract.Operation place = op(c, "OrdersSoap/PlaceOrder");

        assertThat(place.soap().body()).isEqualTo(new SoapTarget.Element("PlaceOrder", "urn:example:order"));
        assertThat(place.requestBody().children()).extracting(PayloadDefinition.Field::name).containsExactly("id", "total", "lines");
        assertThat(place.soap().requestOrder().get("body")).containsExactly("id", "total", "lines");
        assertThat(place.soap().requestNs()).containsEntry("body.id", "").containsEntry("body.lines", "").containsEntry("body.lines.sku", "");
        assertThat(place.soap().action()).isEmpty();
        assertThat(field(place.requestBody(), "lines").items().children()).extracting(PayloadDefinition.Field::name).containsExactly("sku", "qty");
    }

    @Test
    void aSingleUploadedWsdlWithoutItsSchemasWarnsAndShowsText() throws Exception {
        Contract c = importer().importContract("person-registry", "Person registry", resource("person/person.wsdl"), null);
        PayloadDefinition.Field person = field(op(c, "PersonSoap/GetPerson").response(), "person");

        assertThat(c.warnings()).anyMatch(w -> w.contains("schemas/common.xsd"));
        assertThat(field(person, "address").type()).isEqualTo("text");
        assertThat(field(person, "firstName").type()).isEqualTo("text");
    }

    @Test
    void zipImportStoresEveryOriginalFileAndReimportReplacesThemAll() throws Exception {
        Contract c = importer().importZip("person-registry", "Person registry", personZip());
        Path folder = dir.resolve("contracts/person-registry.wsdl");

        assertThat(c.sourceFile()).isEqualTo("person-registry.wsdl/person.wsdl");
        assertThat(Files.readString(folder.resolve("person.wsdl"))).isEqualTo(resource("person/person.wsdl"));
        assertThat(Files.readString(folder.resolve("schemas/common.xsd"))).isEqualTo(resource("person/schemas/common.xsd"));
        assertThat(c.files()).containsOnlyKeys("person.wsdl", "schemas/common.xsd");

        Contract again = importer().importZip("person-registry", "Person registry", zip(Map.of("person.wsdl", resource("person/person.wsdl"))));
        assertThat(Files.exists(folder.resolve("schemas/common.xsd"))).isFalse();
        assertThat(again.files()).containsOnlyKeys("person.wsdl");
        assertThat(Json.MAPPER.readValue(dir.resolve("contracts/person-registry.json").toFile(), Contract.class).operations())
                .hasSameSizeAs(again.operations());
    }

    @Test
    void anOpenApiReimportOfAWsdlContractRemovesTheWsdlFolder() throws Exception {
        importer().importZip("person-registry", "Person registry", personZip());
        importer().importContract("person-registry", "Person registry", ContractImporterTest.fixture(), null);

        assertThat(Files.exists(dir.resolve("contracts/person-registry.wsdl"))).isFalse();
        assertThat(Files.exists(dir.resolve("contracts/person-registry.openapi.json"))).isTrue();
    }

    @Test
    void aZipEntryThatLeavesTheZipIsRefused() throws Exception {
        String wsdl = resource("person/person.wsdl").replace("schemas/common.xsd", "../../etc/schema.xsd");
        assertThatThrownBy(() -> importer().importZip("p", "P", zip(Map.of("person.wsdl", wsdl))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("../../etc/schema.xsd");
        assertThatThrownBy(() -> importer().importZip("p", "P", zip(Map.of("../evil.wsdl", wsdl))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the ZIP");
        assertThat(Files.exists(dir.resolve("contracts/p.json"))).isFalse();
    }

    @Test
    void aWsdlWithADoctypeIsRefusedAndNothingIsStored() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        String wsdl = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\"><documentation>&x;</documentation></definitions>";

        assertThatThrownBy(() -> importer().importContract("evil", "Evil", wsdl, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("TOP-SECRET-VALUE");
        assertThat(Files.exists(dir.resolve("contracts/evil.json"))).isFalse();
    }

    @Test
    void notAContractNamesTheAcceptedTypes() {
        for (String text : List.of("<html><body>hello</body></html>", "{\"hello\":1}")) {
            assertThatThrownBy(() -> importer().importContract("x", "X", text, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OpenAPI 3").hasMessageContaining("Swagger 2.0").hasMessageContaining("WSDL 1.1");
        }
    }

    @Test
    void wsdl2IsRefusedWithAClearMessage() {
        String wsdl2 = "<description xmlns=\"http://www.w3.org/ns/wsdl\" targetNamespace=\"urn:x\"/>";
        assertThatThrownBy(() -> importer().importContract("x", "X", wsdl2, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("WSDL 2.0 is not supported");
    }
}

package integrationmapper.engine;

import java.util.Map;

import com.datasonnet.Mapper;
import com.datasonnet.MapperBuilder;
import com.datasonnet.document.DefaultDocument;
import com.datasonnet.document.MediaTypes;

/**
 * A published version, ready to serve. It keeps its own copies of the Input and Output definitions (D-9), and its
 * DataSonnet scripts are parsed once here and reused for every call (performance principle 2).
 */
public final class CompiledIntegration {

    private final ServiceDefinition definition;
    private final PayloadDefinition input;
    private final PayloadDefinition output;
    private final Mapper request;
    private final Mapper response;

    private CompiledIntegration(ServiceDefinition definition, PayloadDefinition input, PayloadDefinition output,
            Mapper request, Mapper response) {
        this.definition = definition;
        this.input = input;
        this.output = output;
        this.request = request;
        this.response = response;
    }

    /** Generates and parses both scripts; throws if either can't be built, so a broken version is never activated (R9). */
    public static CompiledIntegration compile(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output) {
        try {
            String req = ScriptGenerator.requestScript(def.request().links(), input);
            String res = ScriptGenerator.responseScript(def.response().links(), output);
            return new CompiledIntegration(def, input, output, build(req), build(res));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Integration " + def.id() + " " + def.version() + " does not compile: " + e.getMessage(), e);
        }
    }

    private static Mapper build(String script) {
        return new MapperBuilder(script).withDefaultOutput(MediaTypes.APPLICATION_JSON).build();
    }

    public ServiceDefinition definition() {
        return definition;
    }

    public PayloadDefinition input() {
        return input;
    }

    public PayloadDefinition output() {
        return output;
    }

    public String mapRequest(String inputJson) {
        return run(request, inputJson);
    }

    public String mapResponse(String externalJson) {
        return run(response, externalJson);
    }

    private static String run(Mapper mapper, String json) {
        return mapper.transform(new DefaultDocument<>(json, MediaTypes.APPLICATION_JSON), Map.of(), MediaTypes.APPLICATION_JSON)
                .getContent();
    }
}

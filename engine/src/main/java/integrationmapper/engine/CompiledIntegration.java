package integrationmapper.engine;

import java.util.Map;

import com.datasonnet.Mapper;
import com.datasonnet.MapperBuilder;
import com.datasonnet.document.DefaultDocument;
import com.datasonnet.document.MediaTypes;

/**
 * A published version, ready to serve. The DataSonnet scripts are parsed once here and reused for every call
 * (performance principle 2), instead of being handed to Camel as text per request.
 */
public final class CompiledIntegration {

    private final ServiceDefinition definition;
    private final Mapper request;
    private final Mapper response;

    private CompiledIntegration(ServiceDefinition definition, Mapper request, Mapper response) {
        this.definition = definition;
        this.request = request;
        this.response = response;
    }

    /** Generates and parses both scripts; throws if either can't be built, so a broken version is never activated (R9). */
    public static CompiledIntegration compile(ServiceDefinition def) {
        String req = ScriptGenerator.requestScript(def.request().links());
        String res = ScriptGenerator.responseScript(def.response().links());
        try {
            return new CompiledIntegration(def, build(req), build(res));
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

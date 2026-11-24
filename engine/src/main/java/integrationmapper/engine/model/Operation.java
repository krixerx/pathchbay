package integrationmapper.engine.model;

/**
 * One callable operation of a contract. Request and response bodies are field trees in the same shape as payload
 * definitions, without field ids. Each kind of contract has its own kind of operation.
 */
public sealed interface Operation permits RestOperation, SoapOperation {

    String id();

    String summary();

    PayloadDefinition.Field requestBody();

    PayloadDefinition.Field response();
}

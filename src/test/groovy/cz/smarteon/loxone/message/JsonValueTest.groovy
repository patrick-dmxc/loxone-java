package cz.smarteon.loxone.message

import com.fasterxml.jackson.databind.node.TextNode
import spock.lang.Specification

class JsonValueTest extends Specification implements SerializationSupport {

    def "should deserialize"() {
        expect:
        MAPPER.readValue(json, JsonValue)

        where:
        json << ['""', '123', '{}', '[null, -6]', '{"a":null,"b":34.5}']
    }

    def "should serialize"() {
        expect:
        MAPPER.writeValueAsString(new JsonValue(new TextNode('haha'))) == '"haha"'
    }
}
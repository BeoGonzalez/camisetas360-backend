package com.camisetas360.recommendation.parser;

import com.camisetas360.recommendation.exception.InvalidEventContractException;

/**
 * Strategy interface for parsing and validating raw message byte payloads into typed event models.
 */
public interface OrderEventParser {

    /**
     * Parses and strictly validates a raw event byte payload.
     *
     * @param body raw byte array from the message broker
     * @return validated {@link ParsedOrderEvent}
     * @throws InvalidEventContractException if the payload is malformed or invalid according to contract
     */
    ParsedOrderEvent parse(byte[] body) throws InvalidEventContractException;
}


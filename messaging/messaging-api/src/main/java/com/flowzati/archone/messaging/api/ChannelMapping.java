package com.flowzati.archone.messaging.api;

/** Maps an application-owned logical channel to one physical destination. */
@FunctionalInterface
public interface ChannelMapping {

  String transform(String logicalChannel);
}

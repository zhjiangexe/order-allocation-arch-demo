package com.flowzati.archone.messaging.api;

/** Default mapping used when logical and physical channel names are identical. */
public final class IdentityChannelMapping implements ChannelMapping {

    public static final IdentityChannelMapping INSTANCE = new IdentityChannelMapping();

    private IdentityChannelMapping() {}

    @Override
    public String transform(String logicalChannel) {
        if (logicalChannel == null || logicalChannel.isBlank()) {
            throw new IllegalArgumentException("Logical channel is required");
        }
        return logicalChannel;
    }
}

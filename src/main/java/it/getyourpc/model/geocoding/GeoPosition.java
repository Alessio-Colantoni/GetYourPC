package it.getyourpc.model.geocoding;

public record GeoPosition(String formattedAddress, double latitude, double longitude) {
    private static final int MAX_ADDRESS_LENGTH = 255;

    public GeoPosition {
        formattedAddress = formattedAddress == null ? "" : formattedAddress.replaceAll("\\s+", " ").trim();
        int characters = formattedAddress.codePointCount(0, formattedAddress.length());
        if (characters > MAX_ADDRESS_LENGTH) {
            formattedAddress = formattedAddress.substring(
                    0, formattedAddress.offsetByCodePoints(0, MAX_ADDRESS_LENGTH));
        }
    }
}

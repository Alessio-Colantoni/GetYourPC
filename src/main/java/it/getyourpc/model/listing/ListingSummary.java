package it.getyourpc.model.listing;

import java.math.BigDecimal;
import java.util.List;

public record ListingSummary(
        int id,
        String type,
        BigDecimal price,
        String address,
        String sellerName,
        String sellerEmail,
        String sellerPhone,
        String brand,
        String model,
        BigDecimal screenSize,
        String cpu,
        String motherboard,
        String gpu,
        String ram,
        String memory,
        String power,
        String cpuHeat,
        String pcCase,
        boolean showPhone,
        List<String> photoUrls,
        int reports) {

    public ListingSummary(int id, String type, BigDecimal price, String address, String sellerName, String sellerEmail,
                          String sellerPhone, String brand, String model, BigDecimal screenSize, String cpu,
                          String motherboard, String gpu, String ram, String memory, String power, String cpuHeat,
                          String pcCase, boolean showPhone, List<String> photoUrls) {
        this(id, type, price, address, sellerName, sellerEmail, sellerPhone, brand, model, screenSize, cpu,
                motherboard, gpu, ram, memory, power, cpuHeat, pcCase, showPhone, photoUrls, 0);
    }
}

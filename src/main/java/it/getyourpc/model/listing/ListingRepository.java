package it.getyourpc.model.listing;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.geocoding.GeoPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ListingRepository {
    private static final String SELECT_BASE = """
            SELECT p.id_post, p.price, p.complete_address, u.name, u.surname, u.email,
                   CASE WHEN p.show_phone THEN u.phone ELSE NULL END AS seller_phone,
                   p.show_phone AS show_phone,
                   ((OCTET_LENGTH(p.photo1) >= 3 AND SUBSTRING(p.photo1 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo1) >= 8 AND SUBSTRING(p.photo1 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo1,
                   ((OCTET_LENGTH(p.photo2) >= 3 AND SUBSTRING(p.photo2 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo2) >= 8 AND SUBSTRING(p.photo2 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo2,
                   ((OCTET_LENGTH(p.photo3) >= 3 AND SUBSTRING(p.photo3 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo3) >= 8 AND SUBSTRING(p.photo3 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo3,
                   %s
            FROM PostGeneralInfo p
            JOIN Users u ON u.id_user = p.id_user
            JOIN %s pc ON pc.id_post = p.id_post
            WHERE p.status = 'active' AND u.status = 'active'
              AND p.price BETWEEN :minPrice AND :maxPrice
              AND (6371 * ACOS(LEAST(1, GREATEST(-1,
                    COS(RADIANS(:latitude)) * COS(RADIANS(p.latitude))
                    * COS(RADIANS(p.longitude) - RADIANS(:longitude))
                    + SIN(RADIANS(:latitude)) * SIN(RADIANS(p.latitude)))))) <= :distanceKm
            ORDER BY CASE WHEN :keyword = '' THEN 0
                     WHEN %s ILIKE :keywordPattern ESCAPE '\\' THEN 0 ELSE 1 END,
                     p.id_post DESC
            LIMIT :limit
            """;
    private static final String SELECT_MANAGED_BASE = """
            SELECT p.id_post, p.price, p.complete_address, u.name, u.surname, u.email,
                   CASE WHEN p.show_phone THEN u.phone ELSE NULL END AS seller_phone,
                   CASE WHEN d.id_post IS NOT NULL THEN 'desktop' ELSE 'laptop' END AS listing_type,
                   p.show_phone AS show_phone,
                   COALESCE(d.cpu, l.cpu) AS cpu, d.motherboard,
                   COALESCE(d.gpu, l.gpu) AS gpu, COALESCE(d.ram, l.ram) AS ram,
                   COALESCE(d.memory, l.memory) AS memory, d.power, d.cpu_heat, d.pc_case,
                   l.brand, l.model, l.screen_size,
                   ((OCTET_LENGTH(p.photo1) >= 3 AND SUBSTRING(p.photo1 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo1) >= 8 AND SUBSTRING(p.photo1 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo1,
                   ((OCTET_LENGTH(p.photo2) >= 3 AND SUBSTRING(p.photo2 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo2) >= 8 AND SUBSTRING(p.photo2 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo2,
                   ((OCTET_LENGTH(p.photo3) >= 3 AND SUBSTRING(p.photo3 FROM 1 FOR 3) = DECODE('ffd8ff', 'hex'))
                     OR (OCTET_LENGTH(p.photo3) >= 8 AND SUBSTRING(p.photo3 FROM 1 FOR 8) = DECODE('89504e470d0a1a0a', 'hex'))) AS has_photo3,
                   (SELECT COUNT(*) FROM ListingReport r WHERE r.id_post = p.id_post) AS reports_count
            FROM PostGeneralInfo p
            JOIN Users u ON u.id_user = p.id_user
            LEFT JOIN Desktop d ON d.id_post = p.id_post
            LEFT JOIN Laptop l ON l.id_post = p.id_post
            WHERE p.status = 'active' AND u.status = 'active'
            """;
    private final JdbcClient jdbcClient;
    private final ImageSanitizer imageSanitizer;

    public ListingRepository(JdbcClient jdbcClient, ImageSanitizer imageSanitizer) {
        this.jdbcClient = jdbcClient;
        this.imageSanitizer = imageSanitizer;
    }

    public List<ListingSummary> search(ListingType type, BigDecimal minPrice, BigDecimal maxPrice,
                                       double latitude, double longitude, double distanceKm,
                                       String keyword, int limit) {
        String columns = type == ListingType.DESKTOP
                ? "pc.cpu, pc.motherboard, pc.gpu, pc.ram, pc.memory, pc.power, pc.cpu_heat, pc.pc_case, NULL brand, NULL model, NULL screen_size"
                : "pc.cpu, NULL motherboard, pc.gpu, pc.ram, pc.memory, NULL power, NULL cpu_heat, NULL pc_case, pc.brand, pc.model, pc.screen_size";
        String searchable = type == ListingType.DESKTOP
                ? "CONCAT_WS(' ', p.complete_address, pc.cpu, pc.motherboard, pc.gpu, pc.ram, pc.memory, pc.power, pc.cpu_heat, pc.pc_case)"
                : "CONCAT_WS(' ', p.complete_address, pc.brand, pc.model, pc.cpu, pc.gpu, pc.ram, pc.memory)";
        String sql = SELECT_BASE.formatted(columns,
                type == ListingType.DESKTOP ? "Desktop" : "Laptop", searchable);
        String keywordPattern = "%" + escapeLike(keyword) + "%";
        return jdbcClient.sql(sql)
                .param("minPrice", minPrice).param("maxPrice", maxPrice)
                .param("latitude", latitude).param("longitude", longitude).param("distanceKm", distanceKm)
                .param("keyword", keyword).param("keywordPattern", keywordPattern)
                .param("limit", limit)
                .query((rs, rowNum) -> new ListingSummary(
                        rs.getInt("id_post"), type.name().toLowerCase(), rs.getBigDecimal("price"),
                        rs.getString("complete_address"), rs.getString("name") + " " + rs.getString("surname"),
                        rs.getString("email"), rs.getString("seller_phone"), rs.getString("brand"), rs.getString("model"),
                        rs.getBigDecimal("screen_size"), rs.getString("cpu"), rs.getString("motherboard"),
                        rs.getString("gpu"), rs.getString("ram"), rs.getString("memory"), rs.getString("power"),
                        rs.getString("cpu_heat"), rs.getString("pc_case"), rs.getBoolean("show_phone"), photoUrls(rs.getInt("id_post"),
                        rs.getBoolean("has_photo1"), rs.getBoolean("has_photo2"),
                        rs.getBoolean("has_photo3"))))
                .list();
    }

    public List<ListingSummary> findActiveOwnedBy(int userId) {
        return jdbcClient.sql(SELECT_MANAGED_BASE + """
                        AND p.id_user = :userId
                        ORDER BY p.id_post DESC
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> mapManagedSummary(rs))
                .list();
    }

    public Optional<ListingType> findTypeForOwned(int listingId, int userId) {
        return jdbcClient.sql("""
                        SELECT CASE WHEN d.id_post IS NOT NULL THEN 'desktop' ELSE 'laptop' END AS listing_type
                        FROM PostGeneralInfo p
                        LEFT JOIN Desktop d ON d.id_post = p.id_post
                        LEFT JOIN Laptop l ON l.id_post = p.id_post
                        WHERE p.id_post = :listingId AND p.id_user = :userId AND p.status = 'active'
                        """)
                .param("listingId", listingId)
                .param("userId", userId)
                .query((rs, rowNum) -> ListingType.from(rs.getString("listing_type")))
                .optional();
    }

    public boolean updateGeneral(int listingId, int userId, ListingCreateRequest request,
                                 GeoPosition position, List<byte[]> photos) {
        return jdbcClient.sql("""
                        UPDATE PostGeneralInfo
                        SET photo1 = COALESCE(:photo1, photo1), photo2 = COALESCE(:photo2, photo2), photo3 = COALESCE(:photo3, photo3),
                            price = :price, complete_address = :address, latitude = :latitude, longitude = :longitude, show_phone = :showPhone
                        WHERE id_post = :listingId AND id_user = :userId AND status = 'active'
                        """)
                .param("photo1", photo(photos, 0))
                .param("photo2", photo(photos, 1))
                .param("photo3", photo(photos, 2))
                .param("price", request.price())
                .param("address", position.formattedAddress())
                .param("latitude", position.latitude())
                .param("longitude", position.longitude())
                .param("showPhone", request.showPhone())
                .param("listingId", listingId)
                .param("userId", userId)
                .update() == 1;
    }

    public Optional<GeoPosition> findPositionForOwned(int listingId, int userId) {
        return jdbcClient.sql("""
                        SELECT complete_address, latitude, longitude
                        FROM PostGeneralInfo
                        WHERE id_post = :listingId AND id_user = :userId AND status = 'active'
                        """)
                .param("listingId", listingId)
                .param("userId", userId)
                .query((rs, rowNum) -> new GeoPosition(
                        rs.getString("complete_address"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude")))
                .optional();
    }

    public boolean updateDesktop(int listingId, ListingCreateRequest request) {
        return jdbcClient.sql("""
                        UPDATE Desktop
                        SET cpu = :cpu, motherboard = :motherboard, gpu = :gpu, ram = :ram,
                            memory = :memory, power = :power, cpu_heat = :cpuHeat, pc_case = :pcCase
                        WHERE id_post = :listingId
                        """)
                .param("cpu", request.cpu())
                .param("motherboard", request.motherboard())
                .param("gpu", request.gpu())
                .param("ram", request.ram())
                .param("memory", request.memory())
                .param("power", request.power())
                .param("cpuHeat", request.cpuHeat())
                .param("pcCase", request.pcCase())
                .param("listingId", listingId)
                .update() == 1;
    }

    public boolean updateLaptop(int listingId, ListingCreateRequest request) {
        return jdbcClient.sql("""
                        UPDATE Laptop
                        SET brand = :brand, model = :model, screen_size = :screenSize, cpu = :cpu,
                            gpu = :gpu, ram = :ram, memory = :memory
                        WHERE id_post = :listingId
                        """)
                .param("brand", request.brand())
                .param("model", request.model())
                .param("screenSize", request.screenSize())
                .param("cpu", request.cpu())
                .param("gpu", request.gpu())
                .param("ram", request.ram())
                .param("memory", request.memory())
                .param("listingId", listingId)
                .update() == 1;
    }

    public List<ListingSummary> findAllActive(int limit) {
        return jdbcClient.sql(SELECT_MANAGED_BASE + """
                        ORDER BY p.id_post DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> mapManagedSummary(rs))
                .list();
    }

    public List<ListingSummary> findAllReportedActive(int limit) {
        return jdbcClient.sql(SELECT_MANAGED_BASE + """
                        AND (SELECT COUNT(*) FROM ListingReport r WHERE r.id_post = p.id_post) > 0
                        ORDER BY reports_count DESC, p.id_post DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> mapManagedSummary(rs))
                .list();
    }

    public void insertReport(int listingId, Integer userId) {
        jdbcClient.sql("""
                INSERT INTO ListingReport (id_post, id_user)
                VALUES (:listingId, :userId)
                ON CONFLICT (id_post, id_user) WHERE id_user IS NOT NULL DO NOTHING
                """)
                .param("listingId", listingId)
                .param("userId", userId)
                .update();
    }

    public boolean softDeleteOwned(int listingId, int userId) {
        return jdbcClient.sql("""
                        UPDATE PostGeneralInfo
                        SET status = 'deleted'
                        WHERE id_post = :listingId AND id_user = :userId AND status = 'active'
                        """)
                .param("listingId", listingId)
                .param("userId", userId)
                .update() == 1;
    }

    public Optional<ReviewTarget> lockActiveReviewTarget(int listingId) {
        return jdbcClient.sql("""
                        SELECT p.id_user, u.role
                        FROM PostGeneralInfo p
                        JOIN Users u ON u.id_user = p.id_user
                        WHERE p.id_post = :listingId
                          AND p.status = 'active' AND u.status = 'active'
                        FOR UPDATE OF p, u
                        """)
                .param("listingId", listingId)
                .query((rs, rowNum) -> new ReviewTarget(
                        rs.getInt("id_user"), rs.getString("role")))
                .optional();
    }

    public boolean softRemove(int listingId) {
        return jdbcClient.sql("""
                        UPDATE PostGeneralInfo
                        SET status = 'removed'
                        WHERE id_post = :listingId AND status = 'active'
                        """)
                .param("listingId", listingId)
                .update() == 1;
    }

    public boolean blockActiveUser(int userId) {
        return jdbcClient.sql("""
                        UPDATE Users
                        SET status = 'blocked'
                        WHERE id_user = :userId AND status = 'active' AND LOWER(role) = 'user'
                        """)
                .param("userId", userId)
                .update() == 1;
    }

    public void softRemoveAllActiveOwnedBy(int userId) {
        jdbcClient.sql("""
                        UPDATE PostGeneralInfo
                        SET status = 'removed'
                        WHERE id_user = :userId AND status = 'active'
                        """)
                .param("userId", userId)
                .update();
    }

    @Transactional
    public int insert(AuthenticatedUser user, ListingCreateRequest request, GeoPosition position,
                      List<byte[]> photos, ListingType type) {
        requireActiveUser(user.id());
        int id = insertGeneral(user, request, position, photos);
        if (type == ListingType.DESKTOP) insertDesktop(id, request);
        else insertLaptop(id, request);
        return id;
    }

    private int insertGeneral(AuthenticatedUser user, ListingCreateRequest request, GeoPosition position,
                              List<byte[]> photos) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO PostGeneralInfo
                        (id_user, photo1, photo2, photo3, price, complete_address, latitude, longitude, show_phone, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')
                        """)
                .params(user.id(), photo(photos, 0), photo(photos, 1), photo(photos, 2), request.price(),
                        position.formattedAddress(), position.latitude(), position.longitude(), request.showPhone())
                .update(keyHolder, "id_post");
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("ID annuncio non generato");
        return key.intValue();
    }

    private void insertDesktop(int id, ListingCreateRequest request) {
        jdbcClient.sql("""
                INSERT INTO Desktop (id_post, cpu, motherboard, gpu, ram, memory, power, cpu_heat, pc_case)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, request.cpu(), request.motherboard(), request.gpu(), request.ram(),
                request.memory(), request.power(), request.cpuHeat(), request.pcCase()).update();
    }

    private void insertLaptop(int id, ListingCreateRequest request) {
        jdbcClient.sql("""
                INSERT INTO Laptop (id_post, brand, model, screen_size, cpu, gpu, ram, memory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, request.brand(), request.model(), request.screenSize(), request.cpu(),
                request.gpu(), request.ram(), request.memory()).update();
    }

    public Optional<PhotoData> findPhoto(int listingId, int index) {
        if (index < 1 || index > 3) return Optional.empty();
        String column = "photo" + index;
        return jdbcClient.sql("SELECT p." + column + " FROM PostGeneralInfo p "
                        + "JOIN Users u ON u.id_user = p.id_user "
                        + "WHERE p.id_post = :id AND p.status = 'active' AND u.status = 'active'")
                .param("id", listingId).query((rs, rowNum) -> rs.getBytes(column)).optional()
                .filter(bytes -> bytes != null && bytes.length > 0)
                .flatMap(imageSanitizer::safeStoredImage);
    }

    private void requireActiveUser(int userId) {
        boolean active = jdbcClient.sql("""
                        SELECT 1
                        FROM Users
                        WHERE id_user = :id AND status = 'active'
                        FOR SHARE
                        """)
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!active) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessione non più valida");
        }
    }

    private static List<String> photoUrls(int id, boolean... present) {
        List<String> urls = new ArrayList<>();
        for (int index = 0; index < present.length; index++) {
            if (present[index]) urls.add("/api/listings/" + id + "/photos/" + (index + 1));
        }
        return urls;
    }

    private static byte[] photo(List<byte[]> photos, int index) {
        return photos.size() > index ? photos.get(index) : null;
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ListingSummary mapManagedSummary(ResultSet rs) throws SQLException {
        int listingId = rs.getInt("id_post");
        return new ListingSummary(
                listingId, rs.getString("listing_type"), rs.getBigDecimal("price"),
                rs.getString("complete_address"), rs.getString("name") + " " + rs.getString("surname"),
                rs.getString("email"), rs.getString("seller_phone"), rs.getString("brand"), rs.getString("model"),
                rs.getBigDecimal("screen_size"), rs.getString("cpu"), rs.getString("motherboard"),
                rs.getString("gpu"), rs.getString("ram"), rs.getString("memory"), rs.getString("power"),
                rs.getString("cpu_heat"), rs.getString("pc_case"), rs.getBoolean("show_phone"), photoUrls(listingId,
                rs.getBoolean("has_photo1"), rs.getBoolean("has_photo2"), rs.getBoolean("has_photo3")),
                rs.getInt("reports_count"));
    }

    public record ReviewTarget(int sellerId, String sellerRole) {
    }
}

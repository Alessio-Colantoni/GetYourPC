package it.getyourpc.model.listing;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.geocoding.GeoPosition;
import it.getyourpc.model.review.ReviewService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ListingRepositoryPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbcClient;
    private static ListingRepository repository;
    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db.sql"));
        }
        jdbcClient = JdbcClient.create(dataSource);
        repository = new ListingRepository(jdbcClient, new ImageSanitizer());
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE Laptop, Desktop, PostGeneralInfo, Users RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    void schemaInsertAndGeographicSearchWorkOnPostgres() {
        int userId = insertUser();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", null);
        ListingCreateRequest request = desktopRequest();

        int listingId = repository.insert(user, request,
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        List<ListingSummary> results = repository.search(ListingType.DESKTOP,
                BigDecimal.ZERO, new BigDecimal("1000"), 41.9028, 12.4964, 10, "", 50);

        assertThat(listingId).isPositive();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(listingId);
            assertThat(result.price()).isEqualByComparingTo("799.99");
            assertThat(result.photoUrls()).isEmpty();
        });
    }

    @Test
    void listingCreationRollsBackGeneralAndTechnicalDataTogether() {
        int userId = insertUser();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", null);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        Integer generatedId = transaction.execute(status -> {
            int id = repository.insert(user, desktopRequest(),
                    new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
            status.setRollbackOnly();
            return id;
        });

        assertThat(generatedId).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM PostGeneralInfo")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM Desktop")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void searchOmitsClearlyInvalidLegacyPhotos() throws Exception {
        int userId = insertUser();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", null);
        int listingId = repository.insert(user, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        jdbcClient.sql("UPDATE PostGeneralInfo SET photo1 = :invalid, photo2 = :valid WHERE id_post = :id")
                .param("invalid", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8))
                .param("valid", png())
                .param("id", listingId)
                .update();

        ListingSummary result = repository.search(ListingType.DESKTOP,
                BigDecimal.ZERO, new BigDecimal("1000"), 41.9028, 12.4964, 10, "", 50).get(0);

        assertThat(result.photoUrls()).containsExactly(
                "/api/listings/" + listingId + "/photos/2");
        assertThat(repository.findPhoto(listingId, 1)).isEmpty();
        assertThat(repository.findPhoto(listingId, 2)).isPresent();
    }

    @Test
    void exposesTheSellerPhoneOnlyWhenTheListingOptsIn() {
        int userId = insertUser();
        jdbcClient.sql("UPDATE Users SET phone = '+39 333 1234567' WHERE id_user = :id")
                .param("id", userId).update();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", "+39 333 1234567");
        int hidden = repository.insert(user, desktopRequest(false),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        int visible = repository.insert(user, desktopRequest(true),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);

        List<ListingSummary> results = repository.search(ListingType.DESKTOP,
                BigDecimal.ZERO, new BigDecimal("1000"), 41.9028, 12.4964, 10, "", 50);

        assertThat(results).filteredOn(item -> item.id() == hidden).singleElement()
                .extracting(ListingSummary::sellerPhone).isNull();
        assertThat(results).filteredOn(item -> item.id() == visible).singleElement()
                .extracting(ListingSummary::sellerPhone).isEqualTo("+39 333 1234567");
        assertThat(results).extracting(ListingSummary::sellerEmail)
                .containsOnly("ada@example.com");
    }

    @Test
    void keywordMatchesComeFirstWithoutFilteringOtherListings() {
        int userId = insertUser();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", null);
        int matching = repository.insert(user, desktopRequest("Ryzen 7", false),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        int other = repository.insert(user, desktopRequest("Core i7", false),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);

        List<ListingSummary> results = repository.search(ListingType.DESKTOP,
                BigDecimal.ZERO, new BigDecimal("1000"), 41.9028, 12.4964, 10, "ryzen", 50);

        assertThat(results).extracting(ListingSummary::id).containsExactly(matching, other);
    }

    @Test
    void inactiveUsersCannotInsertOrExposePhotos() throws Exception {
        int userId = insertUser();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "Ada", "Lovelace", "user", "ada@example.com", null);
        int listingId = repository.insert(user, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(png()), ListingType.DESKTOP);
        jdbcClient.sql("UPDATE Users SET status = 'inactive' WHERE id_user = :id")
                .param("id", userId).update();

        assertThat(repository.findPhoto(listingId, 1)).isEmpty();
        assertThatThrownBy(() -> repository.insert(user, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void ownersCanListAndSoftDeleteOnlyTheirOwnActiveListings() {
        int ownerId = insertUser("Ada", "ada@example.com", "user");
        int otherUserId = insertUser("Grace", "grace@example.com", "user");
        AuthenticatedUser owner = new AuthenticatedUser(
                ownerId, "Ada", "Lovelace", "user", "ada@example.com", null);
        int listingId = repository.insert(owner, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);

        assertThat(repository.findActiveOwnedBy(ownerId)).extracting(ListingSummary::id)
                .containsExactly(listingId);
        assertThat(repository.findActiveOwnedBy(otherUserId)).isEmpty();
        assertThat(repository.softDeleteOwned(listingId, otherUserId)).isFalse();
        assertThat(repository.softDeleteOwned(listingId, ownerId)).isTrue();
        assertThat(repository.findActiveOwnedBy(ownerId)).isEmpty();
        assertThat(repository.softDeleteOwned(listingId, ownerId)).isFalse();
        assertThat(postStatus(listingId)).isEqualTo("deleted");
    }

    @Test
    void reviewerCanRemoveAListingAndAtomicallyBlockItsSeller() {
        int reviewerId = insertUser("Rita", "reviewer@example.com", "reviewer");
        int sellerId = insertUser("Ada", "ada@example.com", "user");
        AuthenticatedUser reviewer = new AuthenticatedUser(
                reviewerId, "Rita", "Reviewer", "reviewer", "reviewer@example.com", null);
        AuthenticatedUser seller = new AuthenticatedUser(
                sellerId, "Ada", "Lovelace", "user", "ada@example.com", null);
        int selectedListing = repository.insert(seller, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        int otherListing = repository.insert(seller, desktopRequest(),
                new GeoPosition("Milano, Italia", 45.4642, 9.1900), List.of(), ListingType.DESKTOP);
        ReviewService reviewService = new ReviewService(repository);

        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> reviewService.remove(reviewer, selectedListing, true));

        assertThat(userStatus(sellerId)).isEqualTo("blocked");
        assertThat(postStatus(selectedListing)).isEqualTo("removed");
        assertThat(postStatus(otherListing)).isEqualTo("removed");
        assertThat(repository.findAllActive(100)).isEmpty();
    }

    @Test
    void reviewerCannotBlockItselfOrAnotherReviewer() {
        int reviewerId = insertUser("Rita", "reviewer@example.com", "reviewer");
        AuthenticatedUser reviewer = new AuthenticatedUser(
                reviewerId, "Rita", "Reviewer", "reviewer", "reviewer@example.com", null);
        int listingId = repository.insert(reviewer, desktopRequest(),
                new GeoPosition("Roma, Italia", 41.9028, 12.4964), List.of(), ListingType.DESKTOP);
        ReviewService reviewService = new ReviewService(repository);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> reviewService.remove(reviewer, listingId, true)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");

        assertThat(userStatus(reviewerId)).isEqualTo("active");
        assertThat(postStatus(listingId)).isEqualTo("active");
    }

    private static int insertUser() {
        return insertUser("Ada", "ada@example.com", "user");
    }

    private static int insertUser(String name, String email, String role) {
        return jdbcClient.sql("""
                        INSERT INTO Users (name, surname, role, email, password, status)
                        VALUES (:name, 'Lovelace', :role, :email, :password, 'active')
                        RETURNING id_user
                        """)
                .param("name", name)
                .param("role", role)
                .param("email", email)
                .param("password", "$2y$10$yIR790UTQfEMLAs9.qmPDuFJ1y.6eD9qxrvjkUwLhWwvT9tHk23u.")
                .query(Integer.class).single();
    }

    private static String userStatus(int userId) {
        return jdbcClient.sql("SELECT status FROM Users WHERE id_user = :id")
                .param("id", userId).query(String.class).single();
    }

    private static String postStatus(int listingId) {
        return jdbcClient.sql("SELECT status FROM PostGeneralInfo WHERE id_post = :id")
                .param("id", listingId).query(String.class).single();
    }

    private static ListingCreateRequest desktopRequest() {
        return desktopRequest(false);
    }

    private static ListingCreateRequest desktopRequest(boolean showPhone) {
        return desktopRequest("Ryzen 7", showPhone);
    }

    private static ListingCreateRequest desktopRequest(String cpu, boolean showPhone) {
        return new ListingCreateRequest(
                "desktop", new BigDecimal("799.99"), "Italia", "Roma", null,
                null, null, null, cpu, "B650", "RTX 4070", "32 GB", "1 TB",
                "750 W", "Air", "ATX", showPhone);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

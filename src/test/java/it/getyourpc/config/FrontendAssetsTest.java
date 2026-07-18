package it.getyourpc.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAssetsTest {
    @Test
    void usesTheHtmlTemplateWithCompilerEnabledVue() throws Exception {
        String index = resource("/static/index.html");
        String app = resource("/static/app.js");
        String styles = resource("/static/styles.css");

        assertTrue(index.contains("/webjars/vue/3.5.13/dist/vue.global.prod.js"));
        assertFalse(index.contains("/webjars/vue/3.5.13/dist/vue.runtime.global.prod.js"));
        assertFalse(index.contains("app-render.js"));
        assertFalse(app.contains("GetYourPcRender"));
        assertTrue(index.contains("v-if=\"isReviewer\""));
        assertTrue(index.contains("v-if=\"isUser\""));
        assertTrue(app.contains("/api/listings/mine"));
        assertTrue(app.contains("/api/reviewer/listings"));
        assertTrue(app.contains("blockUser=${blockUser}"));
        assertTrue(app.contains("this.resetSearchResults();"));
        assertTrue(app.contains("if (refreshSearch) await this.searchListings();"));
        assertTrue(index.contains("page === 'register'"));
        assertTrue(index.contains("page === 'forgot-password'"));
        assertTrue(index.contains("page === 'account'"));
        assertTrue(index.contains("page === 'admin'"));
        assertTrue(index.contains("page === 'listing-detail'"));
        assertTrue(app.contains("/api/auth/register/start"));
        assertTrue(app.contains("/api/auth/register/confirm"));
        assertTrue(app.contains("/api/auth/password/forgot/confirm"));
        assertTrue(app.contains("/api/auth/password/change/confirm"));
        assertTrue(app.contains("/api/auth/email/change/confirm"));
        assertTrue(app.contains("/api/auth/profile"));
        assertTrue(app.contains("/api/auth/account"));
        assertTrue(app.contains("/api/admin/reviewers"));
        assertTrue(app.contains("started.deliveryConfirmed"));
        assertTrue(app.contains("Password aggiornata. Accedi di nuovo su questo dispositivo."));
        assertTrue(app.contains("params.set('keyword'"));
        assertTrue(index.contains("max=\"100000\""));
        assertTrue(index.contains("listing.sellerPhone"));
        assertTrue(index.contains("@click=\"openListing(listing)\""));
        assertTrue(app.contains("openListing(listing)"));
        assertTrue(app.contains("closeListing()"));
        assertTrue(styles.contains(".listing-detail{max-width:880px"));
        assertTrue(styles.contains("height:clamp(280px,32vw,380px)"));
        assertFalse(styles.contains("min-height:500px"));
        assertFalse(index.contains("GetYourPC · Spring Boot + Vue"));
        assertTrue(index.contains("<div id=\"app\" v-cloak>"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = FrontendAssetsTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Risorsa non trovata: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

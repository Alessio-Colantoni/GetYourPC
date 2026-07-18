package it.getyourpc.controller.common;

import it.getyourpc.model.auth.LoginRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void missingRequestParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parametro obbligatorio mancante: value"));
    }

    @Test
    void invalidRequestParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/number").param("value", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Valore non valido per il parametro: value"));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/probe/json").contentType(MediaType.APPLICATION_JSON).content("{bad json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Corpo della richiesta non valido"));
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/probe/json").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void transactionConnectionFailureReturnsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/probe/transaction"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Database temporaneamente non disponibile"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/number")
        double number(@RequestParam double value) {
            return value;
        }

        @PostMapping(value = "/probe/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        LoginRequest json(@Valid @RequestBody LoginRequest request) {
            return request;
        }

        @GetMapping("/probe/transaction")
        void transactionFailure() {
            throw new CannotCreateTransactionException("Database offline");
        }
    }
}

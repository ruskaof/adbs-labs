package energosbyt.controller;

import energosbyt.config.JacksonConfiguration;
import energosbyt.service.EnergosbytService;
import energosbyt.repository.EnergosbytRepository;

import java.math.BigInteger;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import energosbyt.dto.Owner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EnergosbytControllerTest {
    private EnergosbytRepository store;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        store = mock(EnergosbytRepository.class);
        http = MockMvcBuilders.standaloneSetup(new EnergosbytController(new EnergosbytService(store)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(new JacksonConfiguration().jsonMapper()))
                .build();
    }

    @Test
    void unsupportedContentTypeReturnsJsonMessage() throws Exception {
        http.perform(patch("/users/1").contentType(MediaType.TEXT_PLAIN).content("{\"fio\":\"Example\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"Unsupported media type\"}"));
        verifyNoInteractions(store);
    }

    @Test
    void unsupportedAcceptReturnsJsonMessage() throws Exception {
        when(store.owner(BigInteger.ONE)).thenReturn(Optional.of(new Owner(BigInteger.ONE, "Name", "phone", "")));
        http.perform(get("/users/1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"Not acceptable\"}"));
    }

    @Test
    void validationErrorRemainsJsonWithUnsupportedAccept() throws Exception {
        http.perform(get("/users/invalid").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("invalid \"id\" parameter"));
    }

    @Test
    void wrongMethodPreservesAllowHeaderAndJsonError() throws Exception {
        http.perform(put("/users/1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(content().json("{\"message\":\"Method not allowed\"}"));
    }

    @Test
    void partialPhoneUpdatePreservesNameAndRemembersPreviousPhone() throws Exception {
        when(store.owner(BigInteger.ONE)).thenReturn(Optional.of(new Owner(BigInteger.ONE, "Name", "old", "older")));
        http.perform(patch("/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"new\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(store).updateOwner(new Owner(BigInteger.ONE, "Name", "new", "old"));
    }

    @Test
    void unchangedPhoneDoesNotOverwritePhoneHistory() throws Exception {
        when(store.owner(BigInteger.ONE)).thenReturn(Optional.of(new Owner(BigInteger.ONE, "Name", "same", "previous")));
        http.perform(patch("/users/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fio\":\"New name\",\"phone\":\"same\"}"))
                .andExpect(status().isNoContent());
        verify(store).updateOwner(new Owner(BigInteger.ONE, "New name", "same", "previous"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"phone\":null}", "{\"fio\":123}", "{\"phone\":true}", "{\"fio\":\" \"}"})
    void invalidPatchDoesNotReachDatabase(String body) throws Exception {
        http.perform(patch("/users/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(store);
    }

    @Test
    void fractionalReadingCounterIsRejected() throws Exception {
        http.perform(post("/realty/1/readings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"user_id":1,"gauge_id":1,"tariff_id":1,"reading_date":"2026-10-01",
                         "day_kw":1.5,"night_kw":3}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid \"day_kw\" field"));
        verifyNoInteractions(store);
    }

    @Test
    void maximumOffsetReturnsEmptyPageWithTotalCount() throws Exception {
        when(store.realtyExists(BigInteger.ONE)).thenReturn(true);
        when(store.invoiceCount(BigInteger.ONE)).thenReturn(36L);
        http.perform(get("/realty/1/invoices").param("offset", "18446744073709551615"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"invoices\":[],\"count\":36}"));
    }
}

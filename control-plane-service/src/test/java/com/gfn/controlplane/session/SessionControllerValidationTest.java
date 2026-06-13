package com.gfn.controlplane.session;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerValidationTest {
    @Test
    void rejectsInvalidCreateSessionRequest() throws Exception {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SessionController(mock(SessionService.class)))
                .setValidator(validator)
                .build();

        mvc.perform(post("/api/v1/sessions")
                        .header("Idempotency-Key", "idem-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userId": "",
                                  "gameId": "cyberpunk2077",
                                  "region": "US_WEST",
                                  "gpuProfile": "ULTRA",
                                  "maxLatencyMs": 1
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}


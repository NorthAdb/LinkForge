package com.linkforge.stats;

import com.linkforge.common.exception.GlobalExceptionHandler;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.stats.controller.StatsController;
import com.linkforge.stats.service.ClickStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Missing {@code dim} used to miss {@link GlobalExceptionHandler}'s BizException
 * mapping and land in the catch-all as HTTP 500. Missing and invalid dims are 400
 * with the usual {@code code}/{@code message} body and no stack trace.
 */
@ExtendWith(MockitoExtension.class)
class BreakdownDimValidationTest {

    @Mock
    private ShortLinkService shortLinkService;
    @Mock
    private ClickStatsService clickStatsService;

    private MockMvc breakdown;
    private MockMvc missingParam;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        breakdown = MockMvcBuilders.standaloneSetup(new StatsController(shortLinkService, clickStatsService))
                .setControllerAdvice(advice)
                .build();
        missingParam = MockMvcBuilders.standaloneSetup(new RequiredParamProbe())
                .setControllerAdvice(advice)
                .build();
    }

    @Test
    void missingBlankAndUnknownDimAre400() throws Exception {
        assertDim400("/api/links/abc/stats/breakdown");
        assertDim400("/api/links/abc/stats/breakdown?dim=");
        assertDim400("/api/links/abc/stats/breakdown?dim=%20");
        assertDim400("/api/links/abc/stats/breakdown?dim=geo");
    }

    @Test
    void missingRequiredParameterDoesNotBecome500() throws Exception {
        missingParam.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("dim is required"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception"))));
    }

    private void assertDim400(String uri) throws Exception {
        breakdown.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(StatsController.DIM_REQUIRED))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\tat "))));
    }

    @RestController
    static class RequiredParamProbe {
        @GetMapping("/probe")
        String probe(@RequestParam String dim) {
            return dim;
        }
    }
}

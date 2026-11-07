package com.linkforge.link.service;

import com.google.zxing.WriterException;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.support.QrParams;
import com.linkforge.link.support.ShareTokenSigner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit fix (exception hygiene): a QR render failure used to surface as a
 * BizException with the original WriterException discarded — and the global
 * handler never logs BizException, so the stack was gone for good. The cause
 * must ride along now.
 */
class QrMaterialServiceCauseTest {

    @Test
    void renderFailureCarriesTheWriterExceptionAsCause() {
        LinkForgeProperties properties = mock(LinkForgeProperties.class);
        when(properties.getPublicBaseUrl()).thenReturn("http://localhost:8080");
        QrMaterialService service = new QrMaterialService(
                mock(ShortLinkService.class), properties, mock(ShareTokenSigner.class));

        // far beyond QR capacity at any error level: encode() raises WriterException
        String oversizedCode = "x".repeat(5000);
        BizException ex = assertThrows(BizException.class,
                () -> service.render(oversizedCode, new QrParams(256, 1, 'M')));
        assertEquals(400, ex.getCode());
        assertInstanceOf(WriterException.class, ex.getCause(),
                "the render failure must keep its original cause, not a bare message");
    }
}

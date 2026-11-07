package com.linkforge.link;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.common.exception.BizException;
import com.linkforge.link.dto.UpdateShortLinkRequest;
import com.linkforge.link.dto.VariantSpec;
import com.linkforge.link.entity.LinkVariant;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.mapper.LinkVariantMapper;
import com.linkforge.link.mapper.ShortLinkMapper;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * L-M1: update() writes the password clear, the link row and the variant
 * replacement in ONE transaction. The failure probe uses a 33-char variant
 * key: the service's own variant validation accepts it (it only checks
 * uniqueness and URL shape), but lf_link_variant.variant_key is VARCHAR(32),
 * so the insert fails at the DB — after the password clear and the row
 * update have already run inside the transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShortLinkUpdateAtomicityIT extends AbstractIntegrationTest {

    private static final long TENANT_ID = 770_001L;
    private static final long USER_ID = 4242L;

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private LinkVariantMapper variantMapper;

    private String code;
    private Long linkId;

    @AfterEach
    void cleanRows() {
        UserContext.clear();
        if (linkId != null) {
            variantMapper.delete(new LambdaQueryWrapper<LinkVariant>()
                    .eq(LinkVariant::getLinkId, linkId));
            shortLinkMapper.deleteById(linkId);
        }
    }

    private void seedLinkWithPasswordAndVariant() {
        // lf_short_link.code is VARCHAR(16): keep the synthetic code short
        code = "upd" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ShortLink link = new ShortLink();
        link.setTenantId(TENANT_ID);
        link.setCode(code);
        link.setOriginalUrl("https://example.com/before");
        link.setStatus(1);
        link.setCreatedBy(USER_ID);
        link.setAccessPasswordHash("$2a$10$seededhashseededhashse");
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.insert(link);
        linkId = link.getId();

        LinkVariant variant = new LinkVariant();
        variant.setTenantId(TENANT_ID);
        variant.setLinkId(linkId);
        variant.setVariantKey("A");
        variant.setTargetUrl("https://example.com/variant-a");
        variant.setWeight(1);
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        variantMapper.insert(variant);

        UserContext.set(new AuthUser(USER_ID, TENANT_ID, "upd-owner", null));
    }

    private UpdateShortLinkRequest updateRequest(String variantKey) {
        VariantSpec spec = new VariantSpec();
        spec.setKey(variantKey);
        spec.setUrl("https://example.com/variant-b");
        spec.setWeight(1);
        UpdateShortLinkRequest request = new UpdateShortLinkRequest();
        request.setOriginalUrl("https://example.com/after");
        request.setClearAccessPassword(true);
        request.setVariants(List.of(spec));
        return request;
    }

    private ShortLink reload() {
        return shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>()
                .eq(ShortLink::getCode, code));
    }

    private List<LinkVariant> variants() {
        return variantMapper.selectList(new LambdaQueryWrapper<LinkVariant>()
                .eq(LinkVariant::getLinkId, linkId));
    }

    @Test
    void failingVariantInsertRollsBackPasswordClearAndRowUpdate() {
        seedLinkWithPasswordAndVariant();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> shortLinkService.update(code, updateRequest("K".repeat(33))));
        assertFalse(thrown instanceof BizException,
                "the probe must fail at the DB write, not in validation: " + thrown);

        ShortLink after = reload();
        assertEquals("https://example.com/before", after.getOriginalUrl(),
                "the row update must roll back with the failed variant insert");
        assertNotNull(after.getAccessPasswordHash(),
                "the password clear must roll back too — it used to commit on its own");
        assertEquals(1, variants().size());
        assertEquals("A", variants().get(0).getVariantKey(), "the old variant set stays intact");
    }

    @Test
    void successfulUpdateAppliesAllThreeWritesTogether() {
        seedLinkWithPasswordAndVariant();

        shortLinkService.update(code, updateRequest("B"));

        ShortLink after = reload();
        assertEquals("https://example.com/after", after.getOriginalUrl());
        assertNull(after.getAccessPasswordHash(), "the password clear landed");
        assertEquals(1, variants().size());
        assertEquals("B", variants().get(0).getVariantKey(), "the variant set was replaced");
    }
}

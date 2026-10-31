package com.linkforge.link.service;

import com.linkforge.link.entity.ShortLink;

/**
 * Verdict of the shared redirect resolution (D-Bw345-51). 404/410/429 stay exceptions;
 * the outcomes here are the gate results the controller renders as HTML or 302.
 */
public sealed interface RedirectOutcome {

    /** Jump: B2 classification and click recording happen on this branch only.
     * variantKey is the drawn A/B bucket (D-C3-11), null on single-target links. */
    record Redirect(ShortLink link, String url, String variantKey) implements RedirectOutcome {
    }

    /** B9 hard block: 403 interstitial, no Location, no target echo. */
    record Blocked() implements RedirectOutcome {
    }

    /** B3: protected link without a valid unlock cookie — GET renders the unlock page,
     * HEAD answers 401. */
    record Locked(ShortLink link) implements RedirectOutcome {
    }

    /** B9 warn: 200 interstitial showing the escaped target; /r/{code}?lf_continue=1 proceeds. */
    record Warn(String code, String url, String asciiHost) implements RedirectOutcome {
    }
}

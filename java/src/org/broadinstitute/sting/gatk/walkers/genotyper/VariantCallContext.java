package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContext;

/**
 * Created by IntelliJ IDEA.
 * User: depristo, ebanks
 * Date: Jan 22, 2010
 * Time: 2:25:19 PM
 *
 * Useful helper class to communicate the results of calculateGenotype to framework
 */
public class VariantCallContext {
    public VariantContext vc = null;
    public String refAllele = null;

    // Was the site called confidently, either reference or variant?
    public boolean confidentlyCalled = false;

    VariantCallContext(VariantContext vc, boolean confidentlyCalledP) {
        this.vc = vc;
        this.confidentlyCalled = confidentlyCalledP;
    }

    VariantCallContext(VariantContext vc, String refAllele, boolean confidentlyCalledP) {
        this.vc = vc;
        this.refAllele = refAllele;
        this.confidentlyCalled = confidentlyCalledP;
    }

    // blank variant context => we're a ref site
    VariantCallContext(boolean confidentlyCalledP) {
        this.confidentlyCalled = confidentlyCalledP;
    }

    public void setRefAllele(byte refAllele) {
        this.refAllele = new String(new byte[]{refAllele});
    }
}
package org.broadinstitute.sting.oneoffprojects.walkers;

import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.StratifiedAlignmentContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.RodVCF;
import org.broadinstitute.sting.gatk.walkers.DataSource;
import org.broadinstitute.sting.gatk.walkers.RMD;
import org.broadinstitute.sting.gatk.walkers.Requires;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.genotype.vcf.VCFGenotypeRecord;
import org.broadinstitute.sting.utils.genotype.vcf.VCFRecord;
import org.broadinstitute.sting.utils.pileup.PileupElement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: chartl
 * Date: Jan 26, 2010
 * Time: 3:25:11 PM
 * To change this template use File | Settings | File Templates.
 */
@Requires(value= DataSource.REFERENCE,referenceMetaData = {@RMD(name="variants",type= RodVCF.class)})
public class AlleleBalanceHistogramWalker extends RodWalker<Map<String,Double>, Map<String,Set<Double>>> {


    public Map<String,Set<Double>> reduceInit() {
        return new HashMap<String,Set<Double>>();
    }

    public Map<String,Set<Double>> reduce(Map<String,Double> alleleBalances, Map<String,Set<Double>> aggregateBalances ) {
        if ( alleleBalances != null ) {
            for ( String name : alleleBalances.keySet() ) {
                if ( alleleBalances.get(name) != null ) {
                    aggregateBalances.get(name).add(alleleBalances.get(name));
                }
            }
        }

        return aggregateBalances;
    }

    public Map<String,Double> map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        RodVCF vcfRod = (RodVCF) tracker.lookup("variants",null);
        if ( vcfRod == null ) {
            return null;
        }
        VCFRecord record = vcfRod.getRecord();

        return getAlleleBalanceBySample(record,ref,context);
    }

    public void onTraversalDone(Map<String,Set<Double>> finalSets) {
        for ( String s : finalSets.keySet() ) {
            StringBuilder output = new StringBuilder();
            output.append(String.format("%s",s));
            for ( double d : finalSets.get(s) ) {
                output.append(String.format("\t%.2f",d));
            }
            out.print(String.format("%s%n",output));
        }
    }

    private HashMap<String,Double> getAlleleBalanceBySample(VCFRecord vcf, ReferenceContext ref, AlignmentContext context) {
        Map<String, StratifiedAlignmentContext> sampleContext = StratifiedAlignmentContext.splitContextBySample(context.getBasePileup(),null,null);
        HashMap<String,Double> balances = new HashMap<String,Double>();
        for ( String sample : vcf.getSampleNames() ) {
            balances.put(sample, getAlleleBalance(ref,sampleContext.get(sample),vcf.getGenotype(sample)));
        }

        return balances;
    }

    private Double getAlleleBalance(ReferenceContext ref, StratifiedAlignmentContext context, VCFGenotypeRecord vcf) {
        int refBases = 0;
        int altBases = 0;
        for ( PileupElement e : context.getContext(StratifiedAlignmentContext.StratifiedContextType.COMPLETE).getBasePileup() ) {
            if ( BaseUtils.basesAreEqual( e.getBase(), (byte) ref.getBase() ) ) {
                refBases++;
            } else if ( BaseUtils.basesAreEqual(e.getBase(), (byte) vcf.toVariation(ref.getBase()).getAlternativeBaseForSNP() ) ) {
                altBases++;
            }
        }

        if ( refBases > 0 || altBases > 0) {
            return ( ( double ) altBases ) / ( ( double ) altBases + ( double ) refBases );
        } else {
            return null;
        }
    }


}

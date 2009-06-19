
package org.broadinstitute.sting.playground.gatk.walkers;

import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMReadGroupRecord;
import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.LocusContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.playground.utils.AlleleFrequencyEstimate;
import org.broadinstitute.sting.playground.utils.*;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.ReadBackedPileup;
import org.broadinstitute.sting.utils.cmdLine.Argument;

import java.util.*;
import java.util.zip.*;
import java.io.*;

// Beta iterative multi-sample caller
// j.maguire 6-11-2009

public class MultiSampleCaller extends LocusWalker<String,String>
{
    @Argument(required=false, shortName="fractional_counts", doc="should we use fractional counts?") public boolean FRACTIONAL_COUNTS = false;
    @Argument(required=false, shortName="max_iterations", doc="Maximum number of iterations for EM") public int MAX_ITERATIONS = 10;
    @Argument(fullName="discovery_output", shortName="discovery_output", required=true, doc="file to write SNP discovery output to")       public String DISCOVERY_OUTPUT;
    @Argument(fullName="individual_output", shortName="individual_output", required=true, doc="file to write individual SNP calls to") public String INDIVIDUAL_OUTPUT;
    @Argument(fullName="sample_name_regex", shortName="sample_name_regex", required=false, doc="sample_name_regex") public String SAMPLE_NAME_REGEX = null;

	// Private state.
    List<String> sample_names;
    private SAMFileHeader header;
	PrintStream individual_output_file;
	PrintStream discovery_output_file;

	/////////
	// Walker Interface Functions 
    public void initialize() 
	{ 
		try
		{
			discovery_output_file = new PrintStream(DISCOVERY_OUTPUT);
			individual_output_file = new PrintStream(new GZIPOutputStream(new FileOutputStream(INDIVIDUAL_OUTPUT)));
			
			discovery_output_file.println("loc ref alt lod strand_score pD pNull discovery_lod in_dbsnp pA pC pG pT EM_alt_freq EM_N n_ref n_het n_hom pD_fw nNull_fw EM_alt_freq_fw pD_bw pNull_bw EM_alt_freq_bw");
			individual_output_file.println("loc ref sample_name genotype lodVsNextBest lodVsRef in_dbsnp AA AC AG AT CC CG CT GG GT TT");
		} 
		catch (Exception e)
		{
			e.printStackTrace(); 
			System.exit(-1);
		}


        GenomeAnalysisEngine toolkit = this.getToolkit();
        this.header = toolkit.getEngine().getSAMHeader();
        List<SAMReadGroupRecord> read_groups = header.getReadGroups();

        sample_names    = new ArrayList<String>();

		HashSet<String> unique_sample_names = new HashSet<String>();

        for (int i = 0; i < read_groups.size(); i++)
        {
            String sample_name = read_groups.get(i).getSample();

			if (SAMPLE_NAME_REGEX != null) { sample_name = sample_name.replaceAll(SAMPLE_NAME_REGEX, "$1"); }

			if (unique_sample_names.contains(sample_name)) { continue; }
			unique_sample_names.add(sample_name);
            sample_names.add(sample_name);
            System.out.println("SAMPLE: " + sample_name);
        } 
    }

	public String in_dbsnp = "novel";

    public String map(RefMetaDataTracker tracker, char ref, LocusContext context) 
	{
		if (ref == 'N') { return null; }
		this.ref = ref;
		if (tracker.lookup("DBSNP", null) != null) { in_dbsnp = "known"; } else { in_dbsnp = "novel"; }
		this.MultiSampleCall(context, sample_names);
		return null;
	}

    public void onTraversalDone(String sum) 
	{
		out.println("MultiSampleCaller done.");
		return;
	}

    public String reduceInit() 
	{
		return null;
	}

    public String reduce(String record, String sum) 
	{
		return null;
	}

	// END Walker Interface Functions
	/////////


	/////////
	// Calling Functions

	char ref;

	GenotypeLikelihoods Genotype(LocusContext context, double[] allele_likelihoods)
	{
        ReadBackedPileup pileup = new ReadBackedPileup(ref, context);
        String bases = pileup.getBases();

		if (bases.length() == 0)
		{
	        GenotypeLikelihoods G = new GenotypeLikelihoods();
	        return G;
		}

        List<SAMRecord> reads = context.getReads();
        List<Integer> offsets = context.getOffsets();
        ref = Character.toUpperCase(ref);

		/*
		// Handle indels.
		if (call_indels)
		{
			String[] indels = BasicPileup.indelPileup(reads, offsets);
			IndelCall indel_call = GenotypeLikelihoods.callIndel(indels);
			if (indel_call != null)
			{
				if (! indel_call.type.equals("ref"))
				{ 
					System.out.printf("INDEL %s %s\n", context.getLocation(), indel_call); 
				}
			}
		}
		*/
        
		// Handle single-base polymorphisms.
        GenotypeLikelihoods G = new GenotypeLikelihoods();
        for ( int i = 0; i < reads.size(); i++ )  
        {
            SAMRecord read = reads.get(i);
            int offset = offsets.get(i);
            G.add(ref, read.getReadString().charAt(offset), read.getBaseQualities()[offset]);
        }
        G.ApplyPrior(ref, allele_likelihoods);

		/*
		// Handle 2nd-best base calls.
        if (fourBaseMode && pileup.getBases().length() < 750) 
		{
            G.applySecondBaseDistributionPrior(pileup.getBases(), pileup.getSecondaryBasePileup());
        }
		*/

        return G;
    }

	// thoughly check this function
	double[] CountFreqs(GenotypeLikelihoods[] genotype_likelihoods)
	{
		double[] allele_likelihoods = new double[4];
		for (int x = 0; x < genotype_likelihoods.length; x++)
		{
			if (genotype_likelihoods[x].coverage == 0) { continue; }
			
			double Z = 0;
			for(int k = 0; k < 10; k++) { Z += Math.pow(10,genotype_likelihoods[x].likelihoods[k]); }

			double[] personal_allele_likelihoods = new double[4];
			int k = 0;
			for (int i = 0; i < 4; i++)
			{ 
				for (int j = i; j < 4; j++)
				{
					personal_allele_likelihoods[i] += Math.pow(10,genotype_likelihoods[x].likelihoods[k])/Z;
					personal_allele_likelihoods[j] += Math.pow(10,genotype_likelihoods[x].likelihoods[k])/Z;
					k++;
				}
			}
			double sum = 0;
			for (int y = 0; y < 4; y++) { sum += personal_allele_likelihoods[y]; }
			for (int y = 0; y < 4; y++) { personal_allele_likelihoods[y] /= sum; }
			for (int y = 0; y < 4; y++) { allele_likelihoods[y] += personal_allele_likelihoods[y]; }
		}

		double sum = 0;
		for (int i = 0; i < 4; i++) { sum += allele_likelihoods[i]; }
		for (int i = 0; i < 4; i++) { allele_likelihoods[i] /= sum; }

		return allele_likelihoods;
	}

	// Potential precision error here.
	double Compute_pD(GenotypeLikelihoods[] genotype_likelihoods)
	{
		double pD = 0;
		for (int i = 0; i < sample_names.size(); i++)
		{
			double sum = 0;
			for (int j = 0; j < 10; j++)
			{
				sum += Math.pow(10, genotype_likelihoods[i].likelihoods[j]);
			}
			pD += Math.log10(sum);
		}
		return pD;
	}

	double Compute_pNull(LocusContext[] contexts)
	{
		double[] allele_likelihoods = new double[4];
		for (int i = 0; i < 4; i++) { allele_likelihoods[i] = 1e-6/3.0; }
		allele_likelihoods[BaseUtils.simpleBaseToBaseIndex(ref)] = 1.0-1e-6;
		GenotypeLikelihoods[] G = new GenotypeLikelihoods[sample_names.size()];
		for (int j = 0; j < sample_names.size(); j++)
		{
			G[j] = Genotype(contexts[j], allele_likelihoods);
		}
		return Compute_pD(G);
	}

	double LOD(LocusContext[] contexts)
	{
		EM_Result em_result = EM(contexts);
		GenotypeLikelihoods[] G = em_result.genotype_likelihoods;
		double pD = Compute_pD(G);
		double pNull = Compute_pNull(contexts);
		double lod = pD - pNull;
		return lod;
	}

	class EM_Result
	{
		GenotypeLikelihoods[] genotype_likelihoods;
		double[] allele_likelihoods;
		int EM_N;
		public EM_Result(GenotypeLikelihoods[] genotype_likelihoods, double[] allele_likelihoods)
		{
			this.genotype_likelihoods = genotype_likelihoods;
			this.allele_likelihoods = allele_likelihoods;

			EM_N = 0;
			for (int i = 0; i < genotype_likelihoods.length; i++) 
			{
				if (genotype_likelihoods[i].coverage > 0) { EM_N += 1; }
			}
		}
	}

	EM_Result EM(LocusContext[] contexts)
	{
		double[] allele_likelihoods = new double[4];

		// These initial conditions should roughly replicate classic SSG. (at least on hets)
		for (int i = 0; i < 4; i++) 
		{ 
			if (i == BaseUtils.simpleBaseToBaseIndex(ref)) { allele_likelihoods[i] = 0.9994999; } //sqrt(0.999) 
			else { allele_likelihoods[i] = 0.0005002502; } // 0.001 / (2 * sqrt(0.999)
		}

		GenotypeLikelihoods[] G = new GenotypeLikelihoods[sample_names.size()];
		for (int i = 0; i < MAX_ITERATIONS; i++)
		{
			for (int j = 0; j < sample_names.size(); j++)
			{
				G[j] = Genotype(contexts[j], allele_likelihoods);
			}
			allele_likelihoods = CountFreqs(G);
		}

		return new EM_Result(G, allele_likelihoods);
	}

	// Hacky global variables for debugging.
	double pNull_fw;
	double pNull_bw;
	double pD_fw;
	double pD_bw;
	double EM_alt_freq_fw;
	double EM_alt_freq_bw;
	double StrandScore(LocusContext context)
	{
		LocusContext[] contexts = filterLocusContextBySample(context, sample_names, 0);

		LocusContext fw = filterLocusContextByStrand(context, "+");
		LocusContext bw = filterLocusContextByStrand(context, "-");
		LocusContext[] contexts_fw = filterLocusContextBySample(fw, sample_names, 0);
		LocusContext[] contexts_bw = filterLocusContextBySample(bw, sample_names, 0);

		EM_Result em_fw = EM(contexts_fw);
		EM_Result em_bw = EM(contexts_bw);

		pNull_fw = Compute_pNull(contexts_fw);
		pNull_bw = Compute_pNull(contexts_bw);

		pD_fw = Compute_pD(em_fw.genotype_likelihoods);
		pD_bw = Compute_pD(em_bw.genotype_likelihoods);

		EM_alt_freq_fw = Compute_alt_freq(ref, em_fw.allele_likelihoods);
		EM_alt_freq_bw = Compute_alt_freq(ref, em_bw.allele_likelihoods);

		double pNull = Compute_pNull(contexts);

		double lod = LOD(contexts);
		double lod_fw = (pD_fw + pNull_bw) - pNull;
		double lod_bw = (pD_bw + pNull_fw) - pNull;
		double strand_score = Math.max(lod_fw - lod, lod_bw - lod);
		return strand_score;
	}

	GenotypeLikelihoods HardyWeinberg(double[] allele_likelihoods)
	{
		GenotypeLikelihoods G = new GenotypeLikelihoods();
		int k = 0;
		for (int i = 0; i < 4; i++)
		{ 
			for (int j = i; j < 4; j++)
			{
				G.likelihoods[k] = allele_likelihoods[i] * allele_likelihoods[j];
				k++;
			}
		}	
		return G;
	}

	char PickAlt(char ref, double[] allele_likelihoods)
	{
		Integer[] perm = Utils.SortPermutation(allele_likelihoods);
		if (perm[3] != BaseUtils.simpleBaseToBaseIndex(ref)) { return BaseUtils.baseIndexToSimpleBase(perm[3]); }
		else { return BaseUtils.baseIndexToSimpleBase(perm[2]); }
	}

	double Compute_discovery_lod(char ref, GenotypeLikelihoods[] genotype_likelihoods)
	{
		double pBest = 0;
		double pRef  = 0;
		for (int i = 0; i < genotype_likelihoods.length; i++)
		{
			pBest += genotype_likelihoods[i].BestPosterior();
			pRef  += genotype_likelihoods[i].RefPosterior(ref);
		}
		return pBest - pRef;
	}

	// this one is a bit of a lazy hack.
	double Compute_alt_freq(char ref, double[] allele_likelihoods)
	{
		return allele_likelihoods[BaseUtils.simpleBaseToBaseIndex(PickAlt(ref, allele_likelihoods))];
	}

	int Compute_n_ref(char ref, GenotypeLikelihoods[] genotype_likelihoods)
	{
		int n = 0;
		for (int i = 0; i < genotype_likelihoods.length; i++)
		{
			if (genotype_likelihoods[i].coverage == 0) { continue; }
			String g = genotype_likelihoods[i].BestGenotype();
			if ((g.charAt(0) == ref) && (g.charAt(1) == ref)) { n += 1; }
		}
		return n;
	}

	int Compute_n_het(char ref, GenotypeLikelihoods[] genotype_likelihoods)
	{
		int n = 0;
		for (int i = 0; i < genotype_likelihoods.length; i++)
		{
			if (genotype_likelihoods[i].coverage == 0) { continue; }
			String g = genotype_likelihoods[i].BestGenotype();
			if ((g.charAt(0) == ref) && (g.charAt(1) != ref)) { n += 1; }
			if ((g.charAt(0) != ref) && (g.charAt(1) == ref)) { n += 1; }
		}
		return n;
	}

	int Compute_n_hom(char ref, GenotypeLikelihoods[] genotype_likelihoods)
	{
		int n = 0;
		for (int i = 0; i < genotype_likelihoods.length; i++)
		{
			if (genotype_likelihoods[i].coverage == 0) { continue; }
			String g = genotype_likelihoods[i].BestGenotype();
			if ((g.charAt(0) != ref) && (g.charAt(1) != ref)) { n += 1; }
		}
		return n;
	}

	// This should actually return a GLF Record
	String MultiSampleCall(LocusContext context, List<String> sample_names) 
	{
		LocusContext[] contexts = filterLocusContextBySample(context, sample_names, 0);
		double lod = LOD(contexts);		
		double strand_score = StrandScore(context);
		EM_Result em_result = EM(contexts);
		GenotypeLikelihoods population_genotype_likelihoods = HardyWeinberg(em_result.allele_likelihoods);	

		double pD = Compute_pD(em_result.genotype_likelihoods);
		double pNull = Compute_pNull(contexts);

		double discovery_lod = Compute_discovery_lod(ref, em_result.genotype_likelihoods);
		double alt_freq      = Compute_alt_freq(ref, em_result.allele_likelihoods);

		char alt = 'N';
	   	if (lod > 0.0) { alt = PickAlt(ref, em_result.allele_likelihoods); }

		int n_ref = Compute_n_ref(ref, em_result.genotype_likelihoods);
		int n_het = Compute_n_het(ref, em_result.genotype_likelihoods);
		int n_hom = Compute_n_hom(ref, em_result.genotype_likelihoods);

		discovery_output_file.printf("%s %c %c %f %f %f %f %f %s ", context.getLocation(), ref, alt, lod, strand_score, pD, pNull, discovery_lod, in_dbsnp);
		for (int i = 0; i < 4; i++) { discovery_output_file.printf("%f ", em_result.allele_likelihoods[i]); }
		discovery_output_file.printf("%f %d %d %d %d %f %f %f %f %f %f\n", alt_freq, em_result.EM_N, n_ref, n_het, n_hom, pD_fw, pNull_fw, EM_alt_freq_fw, pD_bw, pNull_bw, EM_alt_freq_bw);

		for (int i = 0; i < em_result.genotype_likelihoods.length; i++)
		{
			individual_output_file.printf("%s %c %s ", context.getLocation(), ref, sample_names.get(i));
			individual_output_file.printf("%s %f %f %s ", em_result.genotype_likelihoods[i].BestGenotype(), 
													      em_result.genotype_likelihoods[i].LodVsNextBest(),
													      em_result.genotype_likelihoods[i].LodVsRef(ref),
														  in_dbsnp);
			//individual_output.printf("%s ", new ReadBackedPileup(ref, contexts[i]).getBasePileupAsCountsString());
			assert(em_result.genotype_likelihoods[i] != null);
			em_result.genotype_likelihoods[i].sort();
			assert(em_result.genotype_likelihoods[i].sorted_likelihoods != null);
			for (int j = 0; j < em_result.genotype_likelihoods[i].sorted_likelihoods.length; j++)
			{
				individual_output_file.printf("%f ", em_result.genotype_likelihoods[i].likelihoods[j]);
			}
			individual_output_file.printf("\n");
		}

		return null;
	}

	// END Calling Functions
	/////////

	/////////
	// Utility Functions
	
	/// Filter a locus context by forward and backward
	private LocusContext filterLocusContextByStrand(LocusContext context, String strand)
	{
		ArrayList<SAMRecord> reads = new ArrayList<SAMRecord>();
		ArrayList<Integer> offsets = new ArrayList<Integer>();

		for (int i = 0; i < context.getReads().size(); i++)
		{
			SAMRecord read = context.getReads().get(i);
            Integer offset = context.getOffsets().get(i);

			// Filter for strandedness
			if ((!strand.contains("+")) && (read.getReadNegativeStrandFlag() == false)) { continue; }
			if ((!strand.contains("-")) && (read.getReadNegativeStrandFlag() == true))  { continue; }
			reads.add(read);
			offsets.add(offset);
		}
		return new LocusContext(context.getLocation(), reads, offsets);
	}

	// Filter a locus context by sample ID
    private LocusContext[] filterLocusContextBySample(LocusContext context, List<String> sample_names, int downsample)
    {
		HashMap<String,Integer> index = new HashMap<String,Integer>();
		for (int i = 0; i < sample_names.size(); i++)
		{
			index.put(sample_names.get(i), i);
		}

		LocusContext[] contexts = new LocusContext[sample_names.size()];
		ArrayList<SAMRecord>[] reads = new ArrayList[sample_names.size()];
		ArrayList<Integer>[] offsets = new ArrayList[sample_names.size()];

		for (int i = 0; i < sample_names.size(); i++)
		{
			reads[i] = new ArrayList<SAMRecord>();
			offsets[i] = new ArrayList<Integer>();
		}

        for (int i = 0; i < context.getReads().size(); i++)
        {
            SAMRecord read = context.getReads().get(i);
            Integer offset = context.getOffsets().get(i);
            String RG = (String)(read.getAttribute("RG"));

            assert(header != null);
            assert(header.getReadGroup(RG) != null);

            String sample = header.getReadGroup(RG).getSample();
			if (SAMPLE_NAME_REGEX != null) { sample = sample.replaceAll(SAMPLE_NAME_REGEX, "$1"); }
            reads[index.get(sample)].add(read); 
            offsets[index.get(sample)].add(offset); 
        }

        if (downsample != 0)
        {
			for (int j = 0; j < reads.length; j++)
			{
	            List<Integer> perm = new ArrayList<Integer>(); 
	            for (int i = 0; i < reads[j].size(); i++) { perm.add(i); }
	            perm = Utils.RandomSubset(perm, downsample);
	           
	            ArrayList<SAMRecord> downsampled_reads = new ArrayList<SAMRecord>();
	            ArrayList<Integer> downsampled_offsets = new ArrayList<Integer>();
	
	            for (int i = 0; i < perm.size(); i++)
	            {
	                downsampled_reads.add(reads[j].get(perm.get(i)));
	                downsampled_offsets.add(offsets[j].get(perm.get(i)));
	            }
	
	            reads[j] = downsampled_reads;
	            offsets[j] = downsampled_offsets;
				contexts[j] = new LocusContext(context.getLocation(), reads[j], offsets[j]);
			}
        }
		else
		{
			for (int j = 0; j < reads.length; j++)
			{
				contexts[j] = new LocusContext(context.getLocation(), reads[j], offsets[j]);
			}
		}

        return contexts;
    }

	// END Utility functions
	/////////



    
}

package org.broadinstitute.hellbender.tools.sv;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.StructuralVariantType;
import org.broadinstitute.hellbender.tools.spark.sv.utils.GATKSVVCFConstants;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.variant.VariantContextGetters;

import java.util.*;
import java.util.stream.Collectors;

import static org.broadinstitute.hellbender.tools.spark.sv.utils.GATKSVVCFConstants.COPY_NUMBER_FORMAT;

public class SVCallRecord implements SVLocatable {

    public static final String STRAND_PLUS = "+";
    public static final String STRAND_MINUS = "-";
    public static final int UNDEFINED_LENGTH = -1;

    private final String id;
    private final String contigA;
    private final int positionA;
    private final boolean strandA;
    private final String contigB;
    private final int positionB;
    private final boolean strandB;
    private final StructuralVariantType type;
    private int length;
    private final List<String> algorithms;
    private final List<Allele> alleles;
    private final Allele refAllele;
    private final List<Allele> altAlleles;
    private final GenotypesContext genotypes;
    private final Map<String,Object> attributes;    // TODO: utilize this to pass through variant attributes

    public SVCallRecord(final String id,
                        final String contigA,
                        final int positionA,
                        final boolean strandA,
                        final String contigB,
                        final int positionB,
                        final boolean strandB,
                        final StructuralVariantType type,
                        final int length,
                        final List<String> algorithms,
                        final List<Allele> alleles,
                        final List<Genotype> genotypes,
                        final Map<String,Object> attributes) {
        Utils.nonNull(id);
        Utils.nonNull(contigA);
        Utils.nonNull(contigB);
        Utils.nonNull(type);
        Utils.nonNull(algorithms);
        Utils.nonNull(alleles);
        Utils.nonNull(genotypes);
        Utils.nonNull(attributes);
        this.id = id;
        this.contigA = contigA;
        this.positionA = positionA;
        this.strandA = strandA;
        this.contigB = contigB;
        this.positionB = positionB;
        this.strandB = strandB;
        this.type = type;
        this.length = length;
        this.algorithms = Collections.unmodifiableList(algorithms);
        this.alleles = Collections.unmodifiableList(alleles);
        this.altAlleles = alleles.stream().filter(allele -> !allele.isNoCall() && !allele.isReference()).collect(Collectors.toList());
        final List<Allele> refAllelesList = alleles.stream().filter(allele -> !allele.isNoCall() && allele.isReference()).collect(Collectors.toList());
        Utils.validate(refAllelesList.size() <= 1, "Encountered multiple reference alleles");
        this.refAllele = refAllelesList.isEmpty() ? null : refAllelesList.get(0);
        this.genotypes = GenotypesContext.copy(genotypes).immutable();
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    public SVCallRecord(final String id,
                        final String contigA,
                        final int positionA,
                        final boolean strandA,
                        final String contigB,
                        final int positionB,
                        final boolean strandB,
                        final StructuralVariantType type,
                        final int length,
                        final List<String> algorithms,
                        final List<Allele> alleles,
                        final List<Genotype> genotypes) {
        this(id, contigA, positionA, strandA, contigB, positionB, strandB, type, length, algorithms, alleles, genotypes, Collections.emptyMap());
    }

    /**
     * Gets genotypes with non-ref copy number states for a CNV record, as defined by the CN field
     */
    private Collection<Genotype> getCNVCarrierGenotypesByCopyNumber() {
        Utils.validate(isCNV(), "Cannot determine carriers of non-CNV using copy number attribute.");
        Utils.validate(altAlleles.size() <= 1,
                "Carrier samples cannot be determined by copy number for multi-allelic sites. Set sample overlap threshold to 0.");
        if (altAlleles.isEmpty()) {
            return Collections.emptyList();
        }
        final Allele altAllele = altAlleles.get(0);
        return genotypes.stream()
                .filter(g -> isCNVCarrierByCopyNumber(g, altAllele))
                .collect(Collectors.toList());
    }

    /**
     * Returns true if the copy number is non-ref. Note that ploidy is determined using the number of entries in the GT
     * field.
     */
    private static boolean isCNVCarrierByCopyNumber(final Genotype genotype, final Allele altAllele) {
        final int ploidy = genotype.getPloidy();
        if (ploidy == 0 || !genotype.hasExtendedAttribute(COPY_NUMBER_FORMAT)) {
            return false;
        }
        final int copyNumber = VariantContextGetters.getAttributeAsInt(genotype, COPY_NUMBER_FORMAT, 0);
        if (altAllele.equals(Allele.SV_SIMPLE_DEL)) {
            return copyNumber < ploidy;
        } else {
            // DUP
            return copyNumber > ploidy;
        }
    }

    /**
     * Returns sample IDs of carriers using copy number
     */
    private Set<String> getCarrierSamplesByCopyNumber() {
        return getCNVCarrierGenotypesByCopyNumber().stream().map(Genotype::getSampleName).collect(Collectors.toSet());
    }

    /**
     * Returns true if any given genotype has a defined copy number
     */
    private boolean hasDefinedCopyNumbers() {
        return genotypes.stream().anyMatch(g -> g.getExtendedAttribute(COPY_NUMBER_FORMAT, null) != null);
    }

    /**
     * Returns true if any of the given genotypes is called
     */
    private boolean hasExplicitGenotypes() {
        return genotypes.stream().anyMatch(Genotype::isCalled);
    }

    /**
     * Gets sample IDs of called non-ref genotypes
     */
    private Set<String> getCarrierSamplesByGenotype() {
        return genotypes.stream()
                .filter(g -> g.getAlleles().stream().anyMatch(altAlleles::contains))
                .map(Genotype::getSampleName)
                .collect(Collectors.toSet());
    }

    /**
     * Gets carrier sample IDs based on called GT fields. If there are no called genotypes and the record is a CNV and
     * has defined copy number fields, determines carrier status based on copy number state. Returns an empty set if
     * neither is available.
     */
    public Set<String> getCarrierSamples() {
        if (isCNV() && !hasExplicitGenotypes() && hasDefinedCopyNumbers()) {
            return getCarrierSamplesByCopyNumber();
        } else {
            return getCarrierSamplesByGenotype();
        }
    }

    public boolean isDepthOnly() {
        return algorithms.size() == 1 && algorithms.get(0).equals(GATKSVVCFConstants.DEPTH_ALGORITHM);
    }

    public boolean isCNV() {
        return type == StructuralVariantType.DEL || type == StructuralVariantType.DUP || type == StructuralVariantType.CNV;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public String getId() {
        return id;
    }

    @Override
    public String getContigA() {
        return contigA;
    }

    @Override
    public int getPositionA() {
        return positionA;
    }

    @Override
    public String getContigB() {
        return contigB;
    }

    @Override
    public int getPositionB() {
        return positionB;
    }

    public boolean getStrandA() {
        return strandA;
    }

    public boolean getStrandB() {
        return strandB;
    }

    @Override
    public StructuralVariantType getType() {
        return type;
    }

    @Override
    public int getLength() {
        return length;
    }

    public List<String> getAlgorithms() {
        return algorithms;
    }

    public List<Allele> getAlleles() {
        return alleles;
    }

    public List<Allele> getAltAlleles() { return altAlleles; }

    public Allele getRefAllele() { return refAllele; }

    public Set<String> getAllSamples() {
        return genotypes.stream().map(Genotype::getSampleName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public GenotypesContext getGenotypes() {
        return genotypes;
    }

    public SimpleInterval getPositionAInterval() {
        return new SimpleInterval(contigA, positionA, positionA);
    }

    public SimpleInterval getPositionBInterval() {
        return new SimpleInterval(contigB, positionB, positionB);
    }
}

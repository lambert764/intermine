package org.flymine.dataconversion;

/*
 * Copyright (C) 2002-2004 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.FileReader;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.ArrayList;

import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.ModelFactory;

import org.intermine.InterMineException;
import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Reference;
import org.intermine.xml.full.ReferenceList;
import org.intermine.xml.full.ItemHelper;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreFactory;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStoreWriterFactory;
import org.intermine.dataconversion.ItemReader;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.dataconversion.DataTranslator;
import org.intermine.dataconversion.FieldNameAndValue;
import org.intermine.dataconversion.ItemPrefetchDescriptor;
import org.intermine.dataconversion.ItemPrefetchConstraintDynamic;
import org.intermine.dataconversion.ObjectStoreItemReader;
import org.intermine.dataconversion.ObjectStoreItemWriter;
import org.intermine.util.XmlUtil;

import org.apache.log4j.Logger;

/**
 * Convert Ensembl data in fulldata Item format conforming to a source OWL definition
 * to fulldata Item format conforming to InterMine OWL definition.
 *
 * @author Andrew Varley
 * @author Mark Woodbridge
 */
public class EnsemblDataTranslator extends DataTranslator
{
    protected static final Logger LOG = Logger.getLogger(EnsemblDataTranslator.class);

    private Item ensemblDb;
    private Reference ensemblRef;
    private Item emblDb;
    private Reference emblRef;
    private Item tremblDb;
    private Reference tremblRef;
    private Item swissprotDb;
    private Reference swissprotRef;
    private Item flybaseDb;
    private Reference flybaseRef;
    private Map supercontigs = new HashMap();
    private Map scLocs = new HashMap();
    private Map exonLocs = new HashMap();
    private Map exons = new HashMap();
    private Map flybaseIds = new HashMap();
    private String orgAbbrev;
    private Item organism;
    private Reference orgRef;
    private Map proteins = new HashMap();
    private Map proteinIds = new HashMap();
    private Set proteinSynonyms = new HashSet();


    /**
     * @see DataTranslator#DataTranslator
     */
    public EnsemblDataTranslator(ItemReader srcItemReader, OntModel model, String ns,
                                 String orgAbbrev) {
        super(srcItemReader, model, ns);
        this.orgAbbrev = orgAbbrev;
    }

    /**
     * @see DataTranslator#translate
     */
    public void translate(ItemWriter tgtItemWriter)
        throws ObjectStoreException, InterMineException {

        tgtItemWriter.store(ItemHelper.convert(getOrganism()));
        tgtItemWriter.store(ItemHelper.convert(getEnsemblDb()));
        tgtItemWriter.store(ItemHelper.convert(getEmblDb()));
        tgtItemWriter.store(ItemHelper.convert(getTremblDb()));
        tgtItemWriter.store(ItemHelper.convert(getSwissprotDb()));

        super.translate(tgtItemWriter);
        Iterator i = createSuperContigs().iterator();
        while (i.hasNext()) {
            tgtItemWriter.store(ItemHelper.convert((Item) i.next()));
        }
        i = exons.values().iterator();
        while (i.hasNext()) {
            tgtItemWriter.store(ItemHelper.convert((Item) i.next()));
        }
        i = exonLocs.values().iterator();
        while (i.hasNext()) {
            Iterator j = ((Collection) i.next()).iterator();
            while (j.hasNext()) {
                tgtItemWriter.store(ItemHelper.convert((Item) j.next()));
            }
        }
        i = proteins.values().iterator();
        while (i.hasNext()) {
            tgtItemWriter.store(ItemHelper.convert((Item) i.next()));
        }
        i = proteinSynonyms.iterator();
        while (i.hasNext()) {
            tgtItemWriter.store(ItemHelper.convert((Item) i.next()));
        }

        if (flybaseDb != null) {
            tgtItemWriter.store(ItemHelper.convert(flybaseDb));
        }
    }

    /**
     * @see DataTranslator#translateItem
     */
    protected Collection translateItem(Item srcItem)
        throws ObjectStoreException, InterMineException {

        Collection result = new HashSet();
        String srcNs = XmlUtil.getNamespaceFromURI(srcItem.getClassName());
        String className = XmlUtil.getFragmentFromURI(srcItem.getClassName());
        Collection translated = super.translateItem(srcItem);
        if (translated != null) {
            for (Iterator i = translated.iterator(); i.hasNext();) {
                boolean storeTgtItem = true;
                Item tgtItem = (Item) i.next();
                if ("karyotype".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    Item location = createLocation(srcItem, tgtItem, "chromosome", "chr", true);
                    location.addAttribute(new Attribute("strand", "0"));
                    result.add(location);
                } else if ("exon".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    Item stableId = getStableId("exon", srcItem.getIdentifier(), srcNs);
                    if (stableId != null) {
                        moveField(stableId, tgtItem, "stable_id", "identifier");
                    }
                    addReferencedItem(tgtItem, getEnsemblDb(), "evidence", true, "", false);
                    // more than one item representing same exon -> store up in map
                    Item location = createLocation(srcItem, tgtItem, "contig", "contig", true);
                    if (srcItem.hasAttribute("nonUniqueId")) {
                        storeTgtItem = false;
                        processExon(srcItem, tgtItem, location);
                    } else {
                        addToLocations(srcItem.getIdentifier(), location);
                    }
                } else if ("simple_feature".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    tgtItem.addAttribute(new Attribute("identifier", srcItem.getIdentifier()));
                    result.add(createAnalysisResult(srcItem, tgtItem));
                    result.add(createLocation(srcItem, tgtItem, "contig", "contig", true));
                } else if ("prediction_transcript".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    result.add(createLocation(srcItem, tgtItem, "contig", "contig", true));
                } else if ("repeat_feature".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    result.add(createAnalysisResult(srcItem, tgtItem));
                    result.add(createLocation(srcItem, tgtItem, "contig", "contig", true));
                    promoteField(tgtItem, srcItem, "consensus", "repeat_consensus",
                                "repeat_consensus");
                    promoteField(tgtItem, srcItem, "type", "repeat_consensus", "repeat_class");
                    promoteField(tgtItem, srcItem, "identifier", "repeat_consensus", "repeat_name");
                    storeTgtItem = false;
                } else if ("gene".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    addReferencedItem(tgtItem, getEnsemblDb(), "evidence", true, "", false);
                    // gene name should be its stable id (or identifier if none)
                    Item stableId = null;
                    stableId = getStableId("gene", srcItem.getIdentifier(), srcNs);
                    if (stableId != null) {
                         moveField(stableId, tgtItem, "stable_id", "identifier");
                    }
                    if (!tgtItem.hasAttribute("identifier")) {
                        tgtItem.addAttribute(new Attribute("identifier", srcItem.getIdentifier()));
                    }
                    // display_xref is gene name (?)
                    //promoteField(tgtItem, srcItem, "name", "display_xref", "display_label");
                    result.addAll(setGeneSynonyms(srcItem, tgtItem, srcNs));
                    // if no organismDbId set to be same as identifier
                    if (!tgtItem.hasAttribute("organismDbId")) {
                        tgtItem.addAttribute(new Attribute("organismDbId",
                               tgtItem.getAttribute("identifier").getValue()));
                    }

                } else if ("contig".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                    Item relation = createItem(tgtNs + "SimpleRelation", "");
                    addReferencedItem(tgtItem, relation, "subjects", true, "object", false);
                    moveField(srcItem, relation, "clone", "subject");
                    result.add(relation);
                    promoteField(tgtItem, srcItem, "residues", "dna", "sequence");
                } else if ("transcript".equals(className)) {
                    tgtItem.addReference(getOrgRef());

                    Item geneRelation = createItem(tgtNs + "SimpleRelation", "");
                    addReferencedItem(tgtItem, geneRelation, "objects", true, "subject", false);
                    moveField(srcItem, geneRelation, "gene", "object");
                    result.add(geneRelation);

                    String translationId = srcItem.getReference("translation").getRefId();
                    String proteinId = getChosenProteinId(translationId, srcNs);
                    tgtItem.addReference(new Reference("protein", proteinId));
                    Item transRelation = createItem(tgtNs + "SimpleRelation", "");
                    transRelation.addReference(new Reference("subject", proteinId));
                    addReferencedItem(tgtItem, transRelation, "subjects", true, "object", false);
                    result.add(transRelation);
                    // display_labels are not unique
                    //promoteField(tgtItem, srcItem, "identifie", "display_xref", "display_label");
                    // if no identifier set the identifier as name (primary key)
                    if (!tgtItem.hasAttribute("identifier")) {
                        Item stableId = getStableId("transcript", srcItem.getIdentifier(), srcNs);
                        if (stableId != null) {
                            moveField(stableId, tgtItem, "stable_id", "identifier");
                        } else {
                            tgtItem.addAttribute(new Attribute("identifier",
                                                               srcItem.getIdentifier()));
                        }
                    }
                // stable_ids become syonyms, need ensembl Database as source
                } else if (className.endsWith("_stable_id")) {
                    if (className.endsWith("translation_stable_id")) {
                        storeTgtItem = false;
                    } else {
                        tgtItem.addReference(getEnsemblRef());
                        tgtItem.addAttribute(new Attribute("type", "accession"));
                    }
                } else if ("clone".equals(className)) {
                    // clone embl_acc needs to be a synonym in embl database

                    tgtItem.addReference(getOrgRef());
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(tgtItem, synonym, "synonyms", true, "subject", false);
                    moveField(srcItem, synonym, "embl_acc", "value");
                    synonym.addAttribute(new Attribute("type", "accession"));
                    synonym.addReference(getEmblRef());
                    result.add(synonym);
                } else if ("chromosome".equals(className)) {
                    tgtItem.addReference(getOrgRef());
                } else if ("translation".equals(className)) {
                    // no UNIPROT id is available so id will be ensembl stable id
                    // Item stableId = getStableId("translation", srcItem.getIdentifier(), srcNs);
//                     if (stableId != null) {
//                         moveField(stableId, tgtItem, "stable_id", "identifier");
//                     }
                    getProteinByPrimaryAccession(srcItem, srcNs);
                    storeTgtItem = false;
                }

                if (storeTgtItem) {
                    result.add(tgtItem);
                }
            }
        // assembly maps to null but want to create location on a supercontig
        } else if ("assembly".equals(className)) {
            Item sc = getSuperContig(srcItem.getAttribute("superctg_name").getValue(),
                                     srcItem.getReference("chromosome").getRefId(),
                                     Integer.parseInt(srcItem.getAttribute("chr_start").getValue()),
                                     Integer.parseInt(srcItem.getAttribute("chr_end").getValue()),
                                     srcItem.getAttribute("superctg_ori").getValue());

            // locate contig on supercontig
            Item location = createLocation(srcItem, sc, "contig", "superctg", false);
            result.add(location);
        }
        return result;
    }

    /**
     * Translate a "located" Item into an Item and a location
     * @param srcItem the source Item
     * @param tgtItem the target Item (after translation)
     * @param idPrefix the id prefix for this class
     * @param locPrefix the start, end and strand prefix for this class
     * @param srcItemIsChild true if srcItem should be subject of Location
     * @return the location
     */
    protected Item createLocation(Item srcItem, Item tgtItem, String idPrefix, String locPrefix,
                                  boolean srcItemIsChild) {
        String namespace = XmlUtil.getNamespaceFromURI(tgtItem.getClassName());

        Item location = createItem(namespace + "Location", "");

        moveField(srcItem, location, locPrefix + "_start", "start");
        moveField(srcItem, location, locPrefix + "_end", "end");
        location.addAttribute(new Attribute("startIsPartial", "false"));
        location.addAttribute(new Attribute("endIsPartial", "false"));

        if (srcItem.hasAttribute(locPrefix + "_strand")) {
            moveField(srcItem, location, locPrefix + "_strand", "strand");
        }
        if (srcItem.hasAttribute("phase")) {
            moveField(srcItem, location, "phase", "phase");
        }
        if (srcItem.hasAttribute("end_phase")) {
            moveField(srcItem, location, "end_phase", "endPhase");
        }
        if (srcItem.hasAttribute(locPrefix + "_ori")) {
            moveField(srcItem, location, locPrefix + "_ori", "strand");
        }
        if (srcItemIsChild) {
            addReferencedItem(tgtItem, location, "objects", true, "subject", false);
            moveField(srcItem, location, idPrefix, "object");
        } else {
            addReferencedItem(tgtItem, location, "subjects", true, "object", false);
            moveField(srcItem, location, idPrefix, "subject");
        }
        return location;
    }


    /**
     * Create an AnalysisResult pointed to by tgtItem evidence reference.  Move srcItem
     * analysis reference and score to new AnalysisResult.
     * @param srcItem item in src namespace to move fields from
     * @param tgtItem item that will reference AnalysisResult
     * @return new AnalysisResult item
     */
    protected Item createAnalysisResult(Item srcItem, Item tgtItem) {
        Item result = createItem(tgtNs + "ComputationalResult", "");
        moveField(srcItem, result, "analysis", "analysis");
        moveField(srcItem, result, "score", "score");
        result.addReference(getEnsemblRef());
        ReferenceList evidence = new ReferenceList("evidence",
                                     Arrays.asList(new Object[] {result.getIdentifier(),
                                                   getEnsemblDb().getIdentifier()}));
        tgtItem.addCollection(evidence);
        return result;
    }


    private Item getSuperContig(String name, String chrId, int start, int end, String strand) {
        Item supercontig = (Item) supercontigs.get(name);
        if (supercontig == null) {
            supercontig = createItem(tgtNs + "Supercontig", "");
            Item chrLoc = createItem(tgtNs + "Location", "");
            chrLoc.addAttribute(new Attribute("start", "" + Integer.MAX_VALUE));
            chrLoc.addAttribute(new Attribute("end", "" + Integer.MIN_VALUE));
            chrLoc.addAttribute(new Attribute("startIsPartial", "false"));
            chrLoc.addAttribute(new Attribute("endIsPartial", "false"));
            chrLoc.addAttribute(new Attribute("strand", strand));
            chrLoc.addReference(new Reference("subject", supercontig.getIdentifier()));
            chrLoc.addReference(new Reference("object", chrId));

            supercontig.addAttribute(new Attribute("identifier", name));
            ReferenceList subjects = new ReferenceList();
            subjects.setName("subjects");
            supercontig.addCollection(subjects);
            supercontig.addCollection(new ReferenceList("objects",
                           new ArrayList(Collections.singletonList(chrLoc.getIdentifier()))));
            supercontig.addReference(getOrgRef());
            supercontigs.put(name, supercontig);
            scLocs.put(name, chrLoc);
        }

        Item chrLoc = (Item) scLocs.get(name);
        if (Integer.parseInt(chrLoc.getAttribute("start").getValue()) > start) {
            chrLoc.getAttribute("start").setValue("" + start);
        }
        if (Integer.parseInt(chrLoc.getAttribute("end").getValue()) < end) {
            chrLoc.getAttribute("end").setValue("" + end);
        }

        return supercontig;
    }

    private Collection createSuperContigs() {
        Set results = new HashSet();
        Iterator i = supercontigs.values().iterator();
        while (i.hasNext()) {
            Item sc = (Item) i.next();
            results.add(sc);
            results.add((Item) scLocs.get(sc.getAttribute("identifier").getValue()));
        }
        return results;
    }

    private String getChosenProteinId(String id, String srcNs) throws ObjectStoreException {
        String chosenId = (String) proteinIds.get(id);
        if (chosenId == null) {
            Item translation = ItemHelper.convert(srcItemReader.getItemById(id));
            chosenId = getProteinByPrimaryAccession(translation, srcNs).getIdentifier();
            proteinIds.put(id, chosenId);
        }
        return chosenId;
    }

    private Item getProteinByPrimaryAccession(Item srcItem, String srcNs) throws ObjectStoreException {
        Item protein = createItem(tgtNs + "Protein", "");

        Set synonyms = new HashSet();
        String value = srcItem.getIdentifier();
        Set constraints = new HashSet();
        constraints.add(new FieldNameAndValue("className", srcNs + "object_xref", false));
        constraints.add(new FieldNameAndValue("ensembl", value, true));
        Iterator objectXrefs = srcItemReader.getItemsByDescription(constraints).iterator();
        // set specific ids and add synonyms

        String swissProtId = null;
        String tremblId = null;
        String emblId = null;
        while (objectXrefs.hasNext()) {
            Item objectXref = ItemHelper.convert(
                                  (org.intermine.model.fulldata.Item) objectXrefs.next());
            Item xref = ItemHelper.convert(srcItemReader
                        .getItemById(objectXref.getReference("xref").getRefId()));

            String accession = null;
            String dbname = null;
            if (xref != null) {
                accession = xref.getAttribute("dbprimary_acc").getValue();
                Item externalDb = ItemHelper.convert(srcItemReader
                                  .getItemById(xref.getReference("external_db").getRefId()));
                if (externalDb != null) {
                    dbname =  externalDb.getAttribute("db_name").getValue();
                }
            }
            //LOG.error("processing: " + accession + ", " + dbname);

            if (accession != null && !accession.equals("")
                && dbname != null && !dbname.equals("")) {
                if (dbname.equals("SWISSPROT")) {
                    swissProtId = accession;
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(protein, synonym, "synonyms", true, "subject", false);
                    synonym.addAttribute(new Attribute("value", accession));
                    synonym.addAttribute(new Attribute("type", "accession"));
                    synonym.addReference(getSwissprotRef());
                    synonyms.add(synonym);
                } else if (dbname.equals("SPTREMBL")) {
                    tremblId = accession;
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(protein, synonym, "synonyms", true, "subject", false);
                    synonym.addAttribute(new Attribute("value", accession));
                    synonym.addAttribute(new Attribute("type", "accession"));
                    synonym.addReference(getTremblRef());
                    synonyms.add(synonym);
                } else if (dbname.equals("protein_id")) {
                    emblId = accession;
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(protein, synonym, "synonyms", true, "subject", false);
                    synonym.addAttribute(new Attribute("value", accession));
                    synonym.addAttribute(new Attribute("type", "accession"));
                    synonym.addReference(getEmblRef());
                    synonyms.add(synonym);
                } else if (dbname.equals("prediction_SPTREMBL")) {
                    emblId = accession;
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(protein, synonym, "synonyms", true, "subject", false);
                    synonym.addAttribute(new Attribute("value", accession));
                    synonym.addAttribute(new Attribute("type", "accession"));
                    synonym.addReference(getEmblRef());
                    synonyms.add(synonym);
                }
            }
        }

        String primaryAcc = srcItem.getIdentifier();
        if (swissProtId != null) {
            primaryAcc = swissProtId;
        } else if (tremblId != null) {
            primaryAcc = tremblId;
        } else if (emblId != null) {
            primaryAcc = emblId;
        } else {
            // there was no protein accession so use ensembl stable id
            Item stableId = getStableId("translation", srcItem.getIdentifier(), srcNs);
            if (stableId != null) {
                moveField(stableId, protein, "stable_id", "primaryAccession");
            }
        }
        protein.addAttribute(new Attribute("primaryAccession", primaryAcc));

        Item chosenProtein = (Item) proteins.get(primaryAcc);
        if (chosenProtein == null) {
            // set up additional references/collections
            protein.addReference(getOrgRef());
            if (srcItem.hasAttribute("seq_start")) {
                protein.addAttribute(new Attribute("translationStart", srcItem.getAttribute("seq_start").getValue()));
            }
            if (srcItem.hasAttribute("seq_end")) {
                protein.addAttribute(new Attribute("translationEnd", srcItem.getAttribute("seq_end").getValue()));
            }
            if (srcItem.hasReference("start_exon")) {
                protein.addReference(new Reference("startExon", srcItem.getReference("start_exon").getRefId()));
            }
            if (srcItem.hasReference("end_exon")) {
                protein.addReference(new Reference("endExon", srcItem.getReference("end_exon").getRefId()));
            }
            proteins.put(primaryAcc, protein);
            proteinSynonyms.addAll(synonyms);
            chosenProtein = protein;
        }

        proteinIds.put(srcItem.getIdentifier(), chosenProtein.getIdentifier());
        return chosenProtein;
    }

    /**
     * Find external database accession numbers in ensembl to set as Synonyms
     * @param srcItem it in source format ensembl:gene
     * @param tgtItem translate item flymine:Gene
     * @param srcNs namespace of source model
     * @return a set of Synonyms
     * @throws ObjectStoreException if problem retrieving items
     */
    protected Set setGeneSynonyms(Item srcItem, Item tgtItem, String srcNs)
        throws ObjectStoreException {
        // additional gene information is in xref table only accessible via translation
        Set synonyms = new HashSet();
        // get transcript
        Set constraints = new HashSet();
        constraints.add(new FieldNameAndValue("className",
                                              srcNs + "transcript", false));
        constraints.add(new FieldNameAndValue("gene", srcItem.getIdentifier(), true));
        Item transcript = ItemHelper.convert((org.intermine.model.fulldata.Item) srcItemReader
                                        .getItemsByDescription(constraints).iterator().next());

        String translationId = transcript.getReference("translation").getRefId();
        // find xrefs

        constraints = new HashSet();
        constraints.add(new FieldNameAndValue("className", srcNs + "object_xref", false));
        constraints.add(new FieldNameAndValue("ensembl", translationId, true));
        Iterator objectXrefs = srcItemReader.getItemsByDescription(constraints).iterator();
        while (objectXrefs.hasNext()) {
            Item objectXref = ItemHelper.convert(
                                (org.intermine.model.fulldata.Item) objectXrefs.next());
            Item xref = ItemHelper.convert(srcItemReader
                        .getItemById(objectXref.getReference("xref").getRefId()));
            String accession = null;
            String dbname = null;
            if (xref != null) {
                if (xref.hasAttribute("dbprimary_acc")) {
                    accession = xref.getAttribute("dbprimary_acc").getValue();
                    Reference dbRef = xref.getReference("external_db");
                    if (dbRef != null && dbRef.getRefId() != null) {
                        Item externalDb = ItemHelper.convert(srcItemReader
                                                             .getItemById(dbRef.getRefId()));
                        if (externalDb != null) {
                            dbname =  externalDb.getAttribute("db_name").getValue();
                        }
                    }
                }
            }
            if (accession != null && !accession.equals("")
                && dbname != null && !dbname.equals("")) {
                if (dbname.equals("flybase_gene") || dbname.equals("flybase_symbol")) {
                    Item synonym = createItem(tgtNs + "Synonym", "");
                    addReferencedItem(tgtItem, synonym, "synonyms", true, "subject", false);
                    synonym.addAttribute(new Attribute("value", accession));
                    if (dbname.equals("flybase_symbol")) {
                        synonym.addAttribute(new Attribute("type", "name"));
                        tgtItem.addAttribute(new Attribute("name", accession));
                    } else { // flybase_gene
                        synonym.addAttribute(new Attribute("type", "accession"));
                        // temporary fix to deal with broken FlyBase identfiers in ensembl
                        String value = accession;
                        Set idSet = (Set) flybaseIds.get(accession);
                        if (idSet == null) {
                            idSet = new HashSet();
                        } else {
                            value += "_flymine_" + idSet.size();
                        }
                        idSet.add(value);
                        flybaseIds.put(accession, idSet);
                        tgtItem.addAttribute(new Attribute("organismDbId", value));
                    }
                    synonym.addReference(getFlyBaseRef());
                    synonyms.add(synonym);
                }
            }
        }
        return synonyms;
    }

    // keep exon with the lowest identifier
    private void processExon(Item srcItem, Item exon, Item loc) {
        String nonUniqueId = srcItem.getAttribute("nonUniqueId").getValue();
        if (exons.containsKey(nonUniqueId)) {
            Item chosenExon = null;
            Item otherExon = null;
            String oldIdentifier = ((Item) exons.get(nonUniqueId)).getIdentifier();
            if (Integer.parseInt(oldIdentifier.substring(oldIdentifier.indexOf("_") + 1))
                > Integer.parseInt(exon.getIdentifier()
                                   .substring(exon.getIdentifier().indexOf("_") + 1))) {
                chosenExon = exon;
                otherExon = (Item) exons.get(nonUniqueId);
            } else {
                chosenExon = (Item) exons.get(nonUniqueId);
                otherExon = exon;
            }
            // exon in map needs all locations in objects collection
            Set objects = new HashSet();
            objects.addAll(chosenExon.getCollection("objects").getRefIds());
            objects.addAll(otherExon.getCollection("objects").getRefIds());
            objects.add(loc.getIdentifier());
            chosenExon.addCollection(new ReferenceList("objects", new ArrayList(objects)));

            // all locs need chosen exon as subject
            addToLocations(nonUniqueId, loc);
            Iterator iter = ((Collection) exonLocs.get(nonUniqueId)).iterator();
            while (iter.hasNext()) {
                Item location = (Item) iter.next();
                location.addReference(new Reference("subject", chosenExon.getIdentifier()));
            }

            exons.put(nonUniqueId, chosenExon);
        } else {
            exons.put(nonUniqueId, exon);
            addToLocations(nonUniqueId, loc);
        }
    }

    private void addToLocations(String nonUniqueId, Item location) {
        Set locs = (Set) exonLocs.get(nonUniqueId);
        if (locs == null) {
            locs = new HashSet();
            exonLocs.put(nonUniqueId, locs);
        }
        locs.add(location);
    }

    // ensemblType should be part of name before _stable_id
    private Item getStableId(String ensemblType, String identifier, String srcNs) throws
        ObjectStoreException {
        //String value = identifier.substring(identifier.indexOf("_") + 1);
        String value = identifier;
        Set constraints = new HashSet();
        constraints.add(new FieldNameAndValue("className",
                                              srcNs + ensemblType + "_stable_id", false));
        constraints.add(new FieldNameAndValue(ensemblType, value, true));
        Iterator stableIds = srcItemReader.getItemsByDescription(constraints).iterator();

        if (stableIds.hasNext()) {
            return ItemHelper.convert((org.intermine.model.fulldata.Item) stableIds.next());
        } else {
            return null;
        }
    }

    private Item getEnsemblDb() {
        if (ensemblDb == null) {
            ensemblDb = createItem(tgtNs + "Database", "");
            Attribute title = new Attribute("title", "ensembl");
            Attribute url = new Attribute("url", "http://www.ensembl.org");
            ensemblDb.addAttribute(title);
            ensemblDb.addAttribute(url);
        }
        return ensemblDb;
    }

    private Reference getEnsemblRef() {
        if (ensemblRef == null) {
            ensemblRef = new Reference("source", getEnsemblDb().getIdentifier());
        }
        return ensemblRef;
    }

    private Item getEmblDb() {
        if (emblDb == null) {
            emblDb = createItem(tgtNs + "Database", "");
            Attribute title = new Attribute("title", "embl");
            Attribute url = new Attribute("url", "http://www.ebi.ac.uk/embl");
            emblDb.addAttribute(title);
            emblDb.addAttribute(url);
        }
        return emblDb;
    }

    private Reference getEmblRef() {
        if (emblRef == null) {
            emblRef = new Reference("source", getEmblDb().getIdentifier());
        }
        return emblRef;
    }


    private Item getSwissprotDb() {
        if (swissprotDb == null) {
            swissprotDb = createItem(tgtNs + "Database", "");
            Attribute title = new Attribute("title", "Swiss-Prot");
            Attribute url = new Attribute("url", "http://ca.expasy.org/sprot/");
            swissprotDb.addAttribute(title);
            swissprotDb.addAttribute(url);
        }
        return swissprotDb;
    }

    private Reference getSwissprotRef() {
        if (swissprotRef == null) {
            swissprotRef = new Reference("source", getSwissprotDb().getIdentifier());
        }
        return swissprotRef;
    }

    private Item getTremblDb() {
        if (tremblDb == null) {
            tremblDb = createItem(tgtNs + "Database", "");
            Attribute title = new Attribute("title", "TrEMBL");
            Attribute url = new Attribute("url", "http://ca.expasy.org/sprot/");
            tremblDb.addAttribute(title);
            tremblDb.addAttribute(url);
        }
        return tremblDb;
    }

    private Reference getTremblRef() {
        if (tremblRef == null) {
            tremblRef = new Reference("source", getTremblDb().getIdentifier());
        }
        return tremblRef;
    }

    private Item getFlyBaseDb() {
        if (flybaseDb == null) {
            flybaseDb = createItem(tgtNs + "Database", "");
            Attribute title = new Attribute("title", "FlyBase");
            Attribute url = new Attribute("url", "http://www.flybase.org");
            flybaseDb.addAttribute(title);
            flybaseDb.addAttribute(url);
        }
        return flybaseDb;
    }

    private Reference getFlyBaseRef() {
        if (flybaseRef == null) {
            flybaseRef = new Reference("source", getFlyBaseDb().getIdentifier());
        }
        return flybaseRef;
    }

    private Item getOrganism() {
        if (organism == null) {
            organism = createItem(tgtNs + "Organism", "");
            Attribute a1 = new Attribute("abbreviation", orgAbbrev);
            organism.addAttribute(a1);
        }
        return organism;
    }

    private Reference getOrgRef() {
        if (orgRef == null) {
            orgRef = new Reference("organism", getOrganism().getIdentifier());
        }
        return orgRef;
    }

    /**
     * Main method
     * @param args command line arguments
     * @throws Exception if something goes wrong
     */
    public static void main (String[] args) throws Exception {
        String srcOsName = args[0];
        String tgtOswName = args[1];
        String modelName = args[2];
        String format = args[3];
        String namespace = args[4];
        String orgAbbrev = args[5];

        Map paths = new HashMap();
        ItemPrefetchDescriptor desc = new ItemPrefetchDescriptor("repeat_feature.repeat_consensus");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("repeat_consensus", "identifier"));
        paths.put("http://www.flymine.org/model/ensembl#repeat_feature",
               Collections.singleton(desc));

        HashSet descSet = new HashSet();
        //desc = new ItemPrefetchDescriptor("transcript.display_xref");
        //desc.addConstraint(new ItemPrefetchConstraintDynamic("display_xref", "identifier"));
        //descSet.add(desc);

        desc = new ItemPrefetchDescriptor(
                "(transcript.translation");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("translation", "identifier"));
        descSet.add(desc);
        ItemPrefetchDescriptor desc2 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl)");
        desc2.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "ensembl"));
        desc2.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#object_xref", false));
        desc.addPath(desc2);
        ItemPrefetchDescriptor desc3 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl).xref");
        desc3.addConstraint(new ItemPrefetchConstraintDynamic("xref", "identifier"));
        desc2.addPath(desc3);
        ItemPrefetchDescriptor desc4 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl).xref.external_db");
        desc4.addConstraint(new ItemPrefetchConstraintDynamic("external_db", "identifier"));
        desc3.addPath(desc4);

        desc = new ItemPrefetchDescriptor("(transcript <- transcript_stable_id.transcript)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "transcript"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#transcript_stable_id", false));
        descSet.add(desc);

        paths.put("http://www.flymine.org/model/ensembl#transcript", descSet);


        descSet = new HashSet();
        //desc = new ItemPrefetchDescriptor("gene.display_xref");
        //desc.addConstraint(new ItemPrefetchConstraintDynamic("display_xref", "identifier"));
        //descSet.add(desc);
        desc = new ItemPrefetchDescriptor("(gene <- gene_stable_id.gene)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "gene"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#gene_stable_id", false));
        descSet.add(desc);
        desc = new ItemPrefetchDescriptor("(gene <- transcript.gene)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "gene"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#transcript", false));
        desc2 = new ItemPrefetchDescriptor(
                "(gene <- transcript.gene).translation");
        descSet.add(desc);
        desc2.addConstraint(new ItemPrefetchConstraintDynamic("translation", "identifier"));
        desc.addPath(desc2);
        desc3 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl)");
        desc3.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "ensembl"));
        desc3.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#object_xref", false));
        desc2.addPath(desc3);
        desc4 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl).xref");
        desc4.addConstraint(new ItemPrefetchConstraintDynamic("xref", "identifier"));
        desc3.addPath(desc4);
        ItemPrefetchDescriptor desc5 = new ItemPrefetchDescriptor(
                "((gene <- transcript.gene).translation <- object_xref.ensembl).xref.external_db");
        desc5.addConstraint(new ItemPrefetchConstraintDynamic("external_db", "identifier"));
        desc4.addPath(desc5);
        paths.put("http://www.flymine.org/model/ensembl#gene", descSet);

        desc = new ItemPrefetchDescriptor("contig.dna");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("dna", "identifier"));
        paths.put("http://www.flymine.org/model/ensembl#contig", Collections.singleton(desc));

        descSet = new HashSet();
        desc = new ItemPrefetchDescriptor("(translation <- object_xref.ensembl)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "ensembl"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#object_xref", false));
        desc2 = new ItemPrefetchDescriptor(
                "(translation <- object_xref.ensembl).xref");
        desc2.addConstraint(new ItemPrefetchConstraintDynamic("xref", "identifier"));
        desc3 = new ItemPrefetchDescriptor("(translation <- object_xref.ensembl).xref.external_db");
        desc3.addConstraint(new ItemPrefetchConstraintDynamic("external_db", "identifier"));
        desc2.addPath(desc3);
        desc.addPath(desc2);
        descSet.add(desc);
        desc = new ItemPrefetchDescriptor("(translation <- translation_stable_id.translation)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "translation"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#translation_stable_id", false));
        descSet.add(desc);
        paths.put("http://www.flymine.org/model/ensembl#translation", descSet);

        desc = new ItemPrefetchDescriptor("(exon <- exon_stable_id.exon)");
        desc.addConstraint(new ItemPrefetchConstraintDynamic("identifier", "exon"));
        desc.addConstraint(new FieldNameAndValue("className",
                    "http://www.flymine.org/model/ensembl#exon_stable_id", false));
        paths.put("http://www.flymine.org/model/ensembl#exon", Collections.singleton(desc));

        ObjectStore osSrc = ObjectStoreFactory.getObjectStore(srcOsName);
        ItemReader srcItemReader = new ObjectStoreItemReader(osSrc, paths);
        ObjectStoreWriter oswTgt = ObjectStoreWriterFactory.getObjectStoreWriter(tgtOswName);
        ItemWriter tgtItemWriter = new ObjectStoreItemWriter(oswTgt);

        OntModel model = ModelFactory.createOntologyModel();
        model.read(new FileReader(new File(modelName)), null, format);
        DataTranslator dt = new EnsemblDataTranslator(srcItemReader, model, namespace, orgAbbrev);
        model = null;
        dt.translate(tgtItemWriter);
        tgtItemWriter.close();
    }
}

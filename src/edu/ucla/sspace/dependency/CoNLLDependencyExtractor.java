/*
 * Copyright 2010 Keith Stevens
 *
 * This file is part of the S-Space package and is covered under the terms and
 * conditions therein.
 *
 * The S-Space package is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation and distributed hereunder to you.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND NO REPRESENTATIONS OR WARRANTIES,
 * EXPRESS OR IMPLIED ARE MADE.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, WE MAKE
 * NO REPRESENTATIONS OR WARRANTIES OF MERCHANT- ABILITY OR FITNESS FOR ANY
 * PARTICULAR PURPOSE OR THAT THE USE OF THE LICENSED SOFTWARE OR DOCUMENTATION
 * WILL NOT INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR OTHER
 * RIGHTS.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package edu.ucla.sspace.dependency;

import edu.ucla.sspace.text.IteratorFactory;
import edu.ucla.sspace.text.Stemmer;
import edu.ucla.sspace.text.TokenFilter;

import edu.ucla.sspace.util.Duple;
import edu.ucla.sspace.util.MultiMap;
import edu.ucla.sspace.util.HashMultiMap;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * A class for extracting dependency parsed sentences in the <a
 * href="http://nextens.uvt.nl/depparse-wiki/DataFormat">CoNLL format</a>, which
 * are generated by the <a href="http://maltparser.org/index.html">Malt
 * Parser</a>.  The ordering of the CoNLL format can be specified with an xml
 * file following the format specified <a
 * href="http://maltparser.org/userguide.html#inout">here</a>.  This
 * configuration file simply specifies the ordering of the dependency features.
 * By default, the extractor assumes the default format used by the Malt Parser
 *
 * </p>
 *
 * Parsed sentences are returned as an array of {@link DependencyTreeNode}
 * innstances.  The nodes contain relations between each word in the sentence.
 * The nodes in the returned array are ordered by the ordering of word
 * occurrences.
 *
 * </p>
 *
 * This class optionally supports filtering sentences to remove words.  The
 * nodes for those removed words will still remain in the parse tree.
 * Similarly, the relations connecting the removed words will also existing.
 * However, the {@link DependencyTreeNode#word()} method will return {@link
 * IteratorFactory#EMPTY_TOKEN} to indicate that the node's text was filtered
 * out.  Note that the node will still have the original part of speech.
 *
 * @author Keith Stevens
 */
public class CoNLLDependencyExtractor implements DependencyExtractor {

    /**
     * A {@link TokenFilter} that will accept or reject tokens before they are
     * stored in a {@link DependencyTreeNode}, if provided.
     */
    private final TokenFilter filter;

    /**
     * A {@link Stemmer} that will lemmatize tokens before they are stored in a
     * {@link DependencyTreeNode}, if provided.
     */
    private final Stemmer stemmer;

    /**
     * The feature index for the node id.
     */
    private final int idIndex;

    /**
     * The feature index for the word's form.
     */
    private final int formIndex;

    /**
     * The feature index for the word's lemma.
     */
    private final int lemmaIndex;

    /**
     * The feature index for the word's part of speech tag.
     */
    private final int posIndex;

    /**
     * The feature index for the parent, or head, of the current node.
     */
    private final int parentIndex;

    /**
     * The feature index for the relation to the parent, or head, of the current
     * node.
     */
    private final int relationIndex;

    /**
     * Creates a new {@link CoNLLDependencyExtractor} that assumes the default
     * ordering for {@code Malt} dependency parses.
     */
    public CoNLLDependencyExtractor() {
        this(null,null);
    }

    /**
     * Creates a new {@link CoNLLDependencyExtractor} that assumes the default
     * ordering for {@code Malt} dependency parses and uses the given {@link
     * TokenFilter} and {@link Stemmer}.
     */
    public CoNLLDependencyExtractor(TokenFilter filter, Stemmer stemmer) {
        this.filter = filter;
        this.stemmer = stemmer;

        idIndex = 0;
        formIndex = 1;
        lemmaIndex = 2;
        posIndex = 3;
        parentIndex = 6;
        relationIndex = 7;
    }

    /**
     * Creates a new {@link CoNLLDependencyExtractor} that assumes the default
     * ordering for {@code Malt} dependency parses and uses the given {@link
     * TokenFilter} and {@link Stemmer} and the given indices for each feature.
     */
    public CoNLLDependencyExtractor(TokenFilter filter, Stemmer stemmer,
                                    int idIndex, int formIndex, int lemmaIndex,
                                    int posIndex, int parentIndex, 
                                    int relationIndex) {
        this.filter = filter;
        this.stemmer = stemmer;

        this.idIndex = idIndex;
        this.formIndex = formIndex;
        this.lemmaIndex = lemmaIndex;
        this.posIndex = posIndex;
        this.parentIndex = parentIndex;
        this.relationIndex = relationIndex;
    }

    /**
     * Creates a new {@link CoNLLDependencyExtractor} by parsing a {@code Malt}
     * configuration file, which specifies the order in which the output is
     * formatted.
     */
    public CoNLLDependencyExtractor(String configFile) {
        this(configFile, null, null);
    }

    /**
     * Creates a new {@link CoNLLDependencyExtractor} by parsing a {@code Malt}
     * configuration file, which specifies the order in which the output is
     * formatted and uses the given {@link TokenFilter} and {@link Stemmer}.
     */
    public CoNLLDependencyExtractor(String configFile,
                                    TokenFilter filter,
                                    Stemmer stemmer) {
        this.filter = filter;
        this.stemmer = stemmer;

        // Set up non final index values for each feature of interest.
        int id = 0;
        int form = 1;
        int lemma = 2;
        int pos = 3;
        int head = 4;
        int rel = 5;

        try {
            // Set up an XML parser.
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document currentXmlDoc = db.parse(configFile);

            // Traverse through each column entry in the configuration file.
            NodeList columnList = currentXmlDoc.getElementsByTagName("column");
            for (int i = 0; i < columnList.getLength(); ++i) {
                Element column = (Element) columnList.item(i);
                String name = column.getAttribute("name");

                // If the name attribute matches one of the features we need to
                // extract, set the index as the order in which the feature name
                // occurred.
                if (name.equals("ID"))
                    id = i;
                if (name.equals("FORM"))
                    form = i;
                if (name.equals("LEMMA"))
                    lemma = i;
                if (name.equals("POSTAG"))
                    pos = i;
                if (name.equals("HEAD"))
                    head = i;
                if (name.equals("DEPREL"))
                    rel= i;
            }
        } catch (javax.xml.parsers.ParserConfigurationException pce) {
            pce.printStackTrace();
        } catch (org.xml.sax.SAXException saxe) {
            saxe.printStackTrace();
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }

        // Set the final indexes based on what was in the configuration file.
        idIndex = id;
        formIndex = form;
        lemmaIndex = lemma;
        posIndex = pos;
        parentIndex = head;
        relationIndex = rel;
    }
       
    /**
     * Extracts a dependency parse tree from the provided reader.  The tree is
     * assumed to be in the CoNLL format.
     *
     * </p>
     *
     * The CoNNLL features that are of interest are ID, LEMMA or FORM, POSTAG,
     * HEAD, and DEPREL which are the id of the node, the string for the word at
     * this position, the part of speech tag, the parent of this node, and the
     * relation to the parent, respectively.  These features will be extracted
     * and returned as an array based tree structure ordered by the occurrence
     * of the words in the sentence.
     *
     * @param reader a reader containing one or more parse trees in the CoNLL
     *        format
     *
     * @return an array of {@link DependencyTreeNode}s that compose a dependency
     *         tree, or {@code null} if no tree is present in the reader.
     *
     * @throws IOException when errors are encountered during reading
     */
    public DependencyTreeNode[] readNextTree(BufferedReader reader) 
            throws IOException {
        List<SimpleDependencyTreeNode> nodes =
            new ArrayList<SimpleDependencyTreeNode>();

        // When building the tree, keep track of all the relations seen between
        // the nodes.  The nodes need to be linked by DependencyRelations, which
        // need DependencyTreeNode instances.  However, during parsing, we may
        // encounter a forward reference to a Node not yet created, so the map
        // ensures that the relation will still be added.
        MultiMap<Integer,Duple<Integer,String>> relationsToAdd 
            = new HashMultiMap<Integer,Duple<Integer,String>>();

        StringBuilder sb = new StringBuilder();

        // Read each line in the document to extract the feature set for each
        // word in the sentence.
        int id = 0;
        int offset = 0;
        for (String line = null; ((line = reader.readLine()) != null); ) {
            line = line.trim();

            // If a new line is encountered and no lines have been handled yet,
            // skip all new lines.
            if (line.length() == 0 && nodes.size() == 0)
                continue;

            // If a new line is encountered and lines have already been
            // processed, we have finished processing the entire sentence and
            // can stop.
            if (line.length() == 0)
                break;

            sb.append(line).append("\n");

            // CoNLL formats using tabs between features.
            String[] nodeFeatures = line.split("\\s+");

            // Multiple parse trees may be within the same set of lines, so in
            // order for the later parse trees to be linked correctly, we need
            // to create an offset for the parent ids.
            int realId = Integer.parseInt(nodeFeatures[idIndex]);
            if ((realId == 0 && nodes.size() != offset) ||
                (realId == 1 &&
                 nodes.size() != offset && nodes.size() != offset+1))
                offset = nodes.size();

            // Get the node id and the parent node id.
            int parent = Integer.parseInt(nodeFeatures[parentIndex]) - 1 + offset;

            String word = getWord(nodeFeatures);

            String lemma = getLemma(nodeFeatures, word);

            // Get the part of speech of the node.
            String pos = nodeFeatures[posIndex];

            // Get the relation between this node and it's head node.
            String rel = nodeFeatures[relationIndex];

            // Create the new relation.
            SimpleDependencyTreeNode curNode = 
                new SimpleDependencyTreeNode(word, pos, lemma, id);

            // Set the dependency link between this node and it's parent node.
            // If the parent's real index  is negative then the node itself is a
            // root node and has no parent.
            if (parent - offset > 0) {
                // If the parent has already been seen, add the relation
                // directly.
                if (parent < nodes.size()) {
                    SimpleDependencyTreeNode parentNode = nodes.get(parent);
                    DependencyRelation r = new SimpleDependencyRelation(
                        parentNode, rel, curNode);
                    parentNode.addNeighbor(r);
                    curNode.addNeighbor(r);
                }
                // Otherwise, we'll fill in this link once the tree is has been
                // fully seen.
                else { 
                    relationsToAdd.put(id,
                        new Duple<Integer,String>(parent, rel));
                }
            }
            
            // Finally, add the current node to the
            nodes.add(curNode);
            id++;
        }

        if (nodes.size() == 0)
            return null;

        if (relationsToAdd.size() > 0) {
            // Process all the child links that were not handled during the
            // processing of the words.
            for (Map.Entry<Integer,Duple<Integer,String>> parentAndRel :
                     relationsToAdd.entrySet()) {
                SimpleDependencyTreeNode dep = nodes.get(parentAndRel.getKey());
                Duple<Integer,String> d = parentAndRel.getValue();
                SimpleDependencyTreeNode head = nodes.get(d.x);
                DependencyRelation r = new SimpleDependencyRelation(
                    head, d.y, dep);
                head.addNeighbor(r);
                dep.addNeighbor(r);                
            }
        }

        return nodes.toArray(
                new SimpleDependencyTreeNode[nodes.size()]);
    }

    /**
     * Returns a string representation of the word for a given node in the
     * dependency parse tree.  First, the original word is filtered and if the
     * word is not accepted, the empty string is returned.  Accepted words will
     * then be stemmed if either a lemma is provided by the parser or a {@link
     * Stemmer} is provided with a preference given to the parser provided
     * lemma.  If neither case holds, the original term is returned.
     */
    private String getWord(String[] nodeFeatures) {
        String word = nodeFeatures[formIndex].toLowerCase();
        // Filter if neccessary.
        if (filter != null && !filter.accept(word))
            return IteratorFactory.EMPTY_TOKEN;
        return word;
    }

    private String getLemma(String[] nodeFeatures, String word) {
        // Get the lemma and check it's value.  Stem if needed.
        String lemma = nodeFeatures[lemmaIndex];
        if (lemma.equals("_"))
            return (stemmer == null) ? word : stemmer.stem(word);
        return lemma;
    }
}

/*
 * Copyright (c) 2012, Lawrence Livermore National Security, LLC. Produced at
 * the Lawrence Livermore National Laboratory. Written by Keith Stevens,
 * kstevens@cs.ucla.edu OCEC-10-073 All rights reserved. 
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

// Import the required Mallet code.
import cc.mallet.types.Instance
import cc.mallet.types.InstanceList
import cc.mallet.pipe.Pipe
import cc.mallet.pipe.SerialPipes
import cc.mallet.pipe.CharSequence2TokenSequence
import cc.mallet.pipe.TokenSequence2FeatureSequence
import cc.mallet.topics.ParallelTopicModel

// Import some handy code for managing matrices from the S-Space package.
import edu.ucla.sspace.matrix.ArrayMatrix
import edu.ucla.sspace.matrix.MatrixIO
import edu.ucla.sspace.matrix.MatrixIO.Format

// Other standard imports from scala and java libraries.
import scala.collection.JavaConversions.asJavaCollection
import scala.io.Source

import java.io.File
import java.io.PrintWriter


/**
 * A scala wrapper for using <a href="http://mallet.cs.umass.edu/">Mallet</a> on
 * on a simple corpus where each document is on a line in a multi-line file and
 * the tokens in each document can be split using white space.  After the model
 * has been learned Schisel will save the word by topic probabilities and
 * document by topic probabilities as dense matrices.  It will also store the
 * top 10 words per topic to a separate file.
 */
object Schisel {

    /**
     * Returns a mallet {@link Instance} object by splitting the given document
     * into a document id and the document text.  It is assumed that the
     * document id is the first token in the document and the text is all
     * remaining text.  This returns an empty {@link AnyVal} when the text is
     * empty.
     */
    def makeInstance(document: String) = {
        val Array(docId, text) = document.split("\\s+", 2)
        if (text != "")
            new Instance(text, "noLabel", docId, null)
    }

    /**
     * Returns a mallet {@link InstanceList} built from a corpus file with one
     * document per line.  Each line will be transformed into an {@link
     * Instance} and added to the {@link InstanceList}.  Tokens in each document
     * will be tokenized based on whitespace.
     */
    def buildInstanceList(path: String, skip: Int) = {
        val pipes = new SerialPipes(List(new CharSequence2TokenSequence("\\S+"),
                                         new TokenSequence2FeatureSequence()))
        val instanceList = new InstanceList(pipes)
        var count = 0
        for (line <- Source.fromFile(path).getLines)
            // Try to create the instance object from the line.  If no instance
            // was returned, just ignore it and don't add it to the instance
            // list.
            makeInstance(line) match {
                case inst:Instance => {
                    count += 1
                    if (count >= skip)
                        instanceList.addThruPipe(inst)
                }
                case _ =>
            }
        instanceList
    }

    /**
     * Returns the number of available processors.
     */
    def allProcs = Runtime.getRuntime.availableProcessors

    /**
     * Creates and runs a {@link ParallelTopicModel} using Mallet.  The
     * following paramenters will be set automatically before processing.
     */
    def runLDA(instanceList:InstanceList,
               alpha:Double = 50.0, beta:Double = 0.01,
               showTopicInterval:Int = 50, topWordsPerInterval:Int = 10,
               numTopics:Int = 50, numIterations:Int = 500,
               optimizationInterval:Int = 25, optimizationBurnin:Int = 200,
               useSymmetricAlpha:Boolean = false, numThreads:Int = allProcs) = {
        val topicModel = new ParallelTopicModel(numTopics, alpha, beta)
        topicModel.addInstances(instanceList)
        topicModel.setTopicDisplay(showTopicInterval, topWordsPerInterval)
        topicModel.setNumIterations(numIterations)
        topicModel.setOptimizeInterval(optimizationInterval)
        topicModel.setBurninPeriod(optimizationBurnin)
        topicModel.setSymmetricAlpha(useSymmetricAlpha)
        topicModel.setNumThreads(numThreads)
        topicModel.estimate
        topicModel
    }

    /**
     * Prints the top {@code wordsPerTopic} words for each topic in the model
     * and stores the topic words, with one topic per line, in {@code outFile}.
     */
    def printTopWords(outFile:String, topicModel:ParallelTopicModel, 
                      wordsPerTopic:Int) {
        System.err.println("Printing top words")
        val w = new PrintWriter(outFile)
        topicModel.getTopWords(wordsPerTopic).foreach(
            t => w.println(t.mkString(" ")))
        w.close
    }

    /**
     * Prints the document by topic probabilities as a dense matrix with each
     * document as a row and each topic as a column.
     */
    def printDocumentSpace(outFile:String, topicModel:ParallelTopicModel,
                           numDocuments:Int, numTopics:Int) {
        System.err.println("Printing Document Space")
        val tFile = File.createTempFile("ldaTheta", "dat")
        tFile.deleteOnExit
        topicModel.printDocumentTopics(tFile)
        val documentSpace = new ArrayMatrix(numDocuments, numTopics)
        for ((line, row) <- Source.fromFile(tFile).getLines.zipWithIndex;
             if row > 0) {
            val tokens = line.split("\\s+")
            for (Array(col, score) <- tokens.slice(2, tokens.length).sliding(2, 2))
                documentSpace.set(row-1, col.toInt, score.toDouble)
        }
        MatrixIO.writeMatrix(documentSpace, outFile, Format.DENSE_TEXT)
    }

    /**
     * Prints the word by topic probabilities as a dense matrix with each
     * word as a row and each topic as a column.
     */
    def printWordSpace(outFile:String, topicModel:ParallelTopicModel,
                       numTopics:Int) {
        System.err.println("Printing Word Space")
        val tFile = File.createTempFile("ldaTheta", "dat")
        tFile.deleteOnExit
        topicModel.printTopicWordWeights(tFile)

        val wordMap = (Source.fromFile(tFile).getLines.takeWhile { 
            line => line(0) == '0' } map {
            line => line.split("\\s+")(1) }).zipWithIndex.toMap
        val wordSpace = new ArrayMatrix(wordMap.size, numTopics)
        val rowSums = new Array[Double](wordMap.size)
        for (line <- Source.fromFile(tFile).getLines) {
            val Array(col, word, score) = line.split("\\s+")
            val row = wordMap(word)
            wordSpace.set(row, col.toInt, score.toDouble)
            rowSums(row) += score.toDouble
        }
        for (r <- 0 until wordSpace.rows; c <- 0 until wordSpace.columns)
            wordSpace.set(r, c, wordSpace.get(r, c) / rowSums(r))

        MatrixIO.writeMatrix(wordSpace, outFile, Format.DENSE_TEXT)
    }

    def main(args:Array[String]) {
        // Load up the instances for LDA to process.
        val instances = buildInstanceList(args(0), args(4).toInt)

        // Extract the number of desired topics and number of documents.
        val numTopics = args(2).toInt
        val numDocuments = instances.size
        System.err.println("Training model")

        // Run LDA.
        val topicModel = runLDA(instances, numTopics=numTopics,
                                optimizationInterval=args(3).toInt)

        // Print out the top words, the word by topic probabilities, and
        // document by topic probabilities as dense matrices.
        val outBase = args(1)
        printWordSpace(outBase+"-ws.dat", topicModel, numTopics)
        printDocumentSpace(outBase+"-ds.dat.transpose", 
                           topicModel, numDocuments, numTopics)
        printTopWords(outBase + "-ws.dat.top10", topicModel, 10)
        System.err.println("Done")
    }
}

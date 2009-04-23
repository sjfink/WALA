package com.ibm.wala.cast.java.ipa.slicer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ibm.wala.cast.ir.ssa.AstAssertInstruction;
import com.ibm.wala.cast.java.ipa.modref.AstJavaModRef;
import com.ibm.wala.eclipse.util.CancelException;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.impl.PartialCallGraph;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.util.collections.Filter;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.graph.traverse.DFS;

public class AstJavaSlicer extends Slicer {

  /*
   * Use the passed-in SDG
   */
  public static Collection<Statement> computeBackwardSlice(SDG sdg, Collection<Statement> ss) throws IllegalArgumentException,
      CancelException {
    return computeSlice(sdg, ss, true);
  }

  /**
   * @param ss a collection of statements of interest
   * @throws CancelException
   */
  public static Collection<Statement> computeSlice(SDG sdg, Collection<Statement> ss, boolean backward) throws CancelException {
    return new AstJavaSlicer().slice(sdg, ss, backward);
  }

  public static Set<Statement> gatherStatements(CallGraph CG, Collection<CGNode> partialRoots, Filter<SSAInstruction> filter) {
    Set<Statement> result = new HashSet<Statement>();
    for (Iterator<CGNode> ns = DFS.getReachableNodes(CG, partialRoots).iterator(); ns.hasNext();) {
      CGNode n = ns.next();
      IR nir = n.getIR();
      if (nir != null) {
	SSAInstruction insts[] = nir.getInstructions();
	for (int i = 0; i < insts.length; i++) {
          if (filter.accepts(insts[i])) {
            result.add(new NormalStatement(n, i));
	  }
	}
      }
    }

    return result;
  }

  public static Set<Statement> gatherAssertions(CallGraph CG, Collection<CGNode> partialRoots) {
    return gatherStatements(CG, partialRoots, new Filter<SSAInstruction>() {
      public boolean accepts(SSAInstruction o) {
        return o instanceof AstAssertInstruction;
      }
    });
  }

  public static Set<Statement> gatherWrites(CallGraph CG, Collection<CGNode> partialRoots) {
    return gatherStatements(CG, partialRoots, new Filter<SSAInstruction>() {
      public boolean accepts(SSAInstruction o) {
        return o instanceof SSAPutInstruction;
      }
    });
  }

  public static Pair<Collection<Statement>, SDG> computeAssertionSlice(CallGraph CG, PointerAnalysis pa,
      Collection<CGNode> partialRoots, boolean multiThreadedCode) throws IllegalArgumentException, CancelException {
    CallGraph pcg = PartialCallGraph.make(CG, new LinkedHashSet<CGNode>(partialRoots));
    SDG sdg = new SDG(pcg, pa, new AstJavaModRef(), DataDependenceOptions.FULL, ControlDependenceOptions.FULL);
    System.err.println(("SDG:\n" + sdg));
    Set<Statement> stmts = gatherAssertions(CG, partialRoots);
    if (multiThreadedCode) {
      stmts.addAll(gatherWrites(CG, partialRoots));
    }
    return Pair.make(AstJavaSlicer.computeBackwardSlice(sdg, stmts), sdg);
  }

}

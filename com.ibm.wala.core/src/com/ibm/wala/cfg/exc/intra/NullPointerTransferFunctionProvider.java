package com.ibm.wala.cfg.exc.intra;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.Util;
import com.ibm.wala.dataflow.graph.AbstractMeetOperator;
import com.ibm.wala.dataflow.graph.ITransferFunctionProvider;
import com.ibm.wala.fixpoint.UnaryOperator;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.IVisitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;

/**
 * @author Juergen Graf <graf@kit.edu>
 *
 */
class NullPointerTransferFunctionProvider<T extends ISSABasicBlock> implements ITransferFunctionProvider<T, NullPointerState> {

  private final AbstractMeetOperator<NullPointerState> meet = NullPointerState.meetOperator();
  private final TransferFunctionSSAVisitor visitor;
  private final ControlFlowGraph<SSAInstruction, T> cfg;
  
  
  NullPointerTransferFunctionProvider(ControlFlowGraph<SSAInstruction, T> cfg, IR ir) {
    this.visitor = new TransferFunctionSSAVisitor(ir);
    this.cfg = cfg;
  }
  
  static <T extends ISSABasicBlock> SSAInstruction getRelevantInstruction(T block) {
    SSAInstruction instr = null;
    if (block.getLastInstructionIndex() >= 0) {
      instr = block.getLastInstruction();
    }
    
    if (instr == null && block.isCatchBlock()) {
      if (block instanceof IExplodedBasicBlock) {
        instr = ((IExplodedBasicBlock) block).getCatchInstruction();
      } else if (block instanceof SSACFG.ExceptionHandlerBasicBlock) {
        instr = ((SSACFG.ExceptionHandlerBasicBlock) block).getCatchInstruction();
      } else {
        throw new IllegalStateException("Unable to get catch instruction from unknown ISSABasicBlock implementation.");
      }
    }
    
    return instr;
  }
  
  /* (non-Javadoc)
   * @see com.ibm.wala.dataflow.graph.ITransferFunctionProvider#getEdgeTransferFunction(java.lang.Object, java.lang.Object)
   */
  public UnaryOperator<NullPointerState> getEdgeTransferFunction(T src, T dst) {
    SSAInstruction instr = getRelevantInstruction(src);
    
    if (instr != null && cfg.hasEdge(src, dst)) {
      instr.visit(visitor);
      if (visitor.noIdentity) {
        // do stuff
        if (Util.endsWithConditionalBranch(cfg, src)) {
          if (Util.getTakenSuccessor(cfg, src) == dst) {
            // condition is true -> take function 1
            return visitor.transfer1;
          } else if (Util.getNotTakenSuccessor(cfg, src) == dst) {
            // condition is true -> take function 2
            return visitor.transfer2;
          } else {
            throw new IllegalStateException("Successor of if clause is neither true nor false case.");
          }
        } else {
          if (cfg.getNormalSuccessors(src).contains(dst)) {
            // normal case without exception -> take function 1
            return visitor.transfer1;
          } else if (cfg.getExceptionalSuccessors(src).contains(dst)) {
            // exception has been raised -> take function 2
            return visitor.transfer2;
          } else {
            throw new IllegalStateException("Successor not found.");
          }
        }
      }
    }
    
    return NullPointerState.identityFunction();
  }

  /* (non-Javadoc)
   * @see com.ibm.wala.dataflow.graph.ITransferFunctionProvider#getMeetOperator()
   */
  public AbstractMeetOperator<NullPointerState> getMeetOperator() {
    return meet;
  }

  /* (non-Javadoc)
   * @see com.ibm.wala.dataflow.graph.ITransferFunctionProvider#getNodeTransferFunction(java.lang.Object)
   */
  public UnaryOperator<NullPointerState> getNodeTransferFunction(T node) {
    throw new UnsupportedOperationException("We do not have such a thing dude!");
  }

  /* (non-Javadoc)
   * @see com.ibm.wala.dataflow.graph.ITransferFunctionProvider#hasEdgeTransferFunctions()
   */
  public boolean hasEdgeTransferFunctions() {
    return true;
  }

  /* (non-Javadoc)
   * @see com.ibm.wala.dataflow.graph.ITransferFunctionProvider#hasNodeTransferFunctions()
   */
  public boolean hasNodeTransferFunctions() {
    return false;
  }
  
  private static class TransferFunctionSSAVisitor implements IVisitor {

    private final SymbolTable sym;

    // used for true case of if clause and non-exception path
    private UnaryOperator<NullPointerState> transfer1 = NullPointerState.identityFunction();
    
    // used for false case of if clause and exceptional path
    private UnaryOperator<NullPointerState> transfer2 = NullPointerState.identityFunction();
    
    // true if sth will change. false => just use identity transfer function.
    private boolean noIdentity = false;
    
    private TransferFunctionSSAVisitor(IR ir) {
      this.sym = ir.getSymbolTable();
    }
    
    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayLength(com.ibm.wala.ssa.SSAArrayLengthInstruction)
     */
    public void visitArrayLength(SSAArrayLengthInstruction instruction) {
      noIdentity = true;
      transfer1 = NullPointerState.denullifyFunction(instruction.getArrayRef());
      transfer2 = NullPointerState.nullifyFunction(instruction.getArrayRef());
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayLoad(com.ibm.wala.ssa.SSAArrayLoadInstruction)
     */
    public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
      noIdentity = true;
      transfer1 = NullPointerState.denullifyFunction(instruction.getArrayRef());
      transfer2 = NullPointerState.nullifyFunction(instruction.getArrayRef());
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayStore(com.ibm.wala.ssa.SSAArrayStoreInstruction)
     */
    public void visitArrayStore(SSAArrayStoreInstruction instruction) {
      noIdentity = true;
      transfer1 = NullPointerState.denullifyFunction(instruction.getArrayRef());
      transfer2 = NullPointerState.nullifyFunction(instruction.getArrayRef());
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitBinaryOp(com.ibm.wala.ssa.SSABinaryOpInstruction)
     */
    public void visitBinaryOp(SSABinaryOpInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitCheckCast(com.ibm.wala.ssa.SSACheckCastInstruction)
     */
    public void visitCheckCast(SSACheckCastInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitComparison(com.ibm.wala.ssa.SSAComparisonInstruction)
     */
    public void visitComparison(SSAComparisonInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitConditionalBranch(com.ibm.wala.ssa.SSAConditionalBranchInstruction)
     */
    public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {
      int arg1 = instruction.getUse(0);
      int arg2 = instruction.getUse(1);
      IConditionalBranchInstruction.IOperator testOp = instruction.getOperator();
      if (!(testOp instanceof IConditionalBranchInstruction.Operator)) {
        throw new IllegalStateException("Conditional operator of unknown type: " + testOp.getClass());
      }
      IConditionalBranchInstruction.Operator op = (IConditionalBranchInstruction.Operator) testOp;

      if (sym.isNullConstant(arg1)) {
        switch (op) {
        case EQ:
          noIdentity = true;
          transfer1 = NullPointerState.nullifyFunction(arg2);
          transfer2 = NullPointerState.denullifyFunction(arg2);
          break;
        case NE:
          noIdentity = true;
          transfer1 = NullPointerState.denullifyFunction(arg2);
          transfer2 = NullPointerState.nullifyFunction(arg2);
          break;
        default:
          throw new IllegalStateException("Comparision to a null constant using " + op);
        }
      } else if (sym.isNullConstant(arg2)) {
        switch (op) {
        case EQ:
          noIdentity = true;
          transfer1 = NullPointerState.nullifyFunction(arg1);
          transfer2 = NullPointerState.denullifyFunction(arg1);
          break;
        case NE:
          noIdentity = true;
          transfer1 = NullPointerState.denullifyFunction(arg1);
          transfer2 = NullPointerState.nullifyFunction(arg1);
          break;
        default:
          throw new IllegalStateException("Comparision to a null constant using " + op);
        }
      } else {
        noIdentity = false;
        transfer1 = NullPointerState.identityFunction();
        transfer2 = NullPointerState.identityFunction();
      }
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitConversion(com.ibm.wala.ssa.SSAConversionInstruction)
     */
    public void visitConversion(SSAConversionInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGet(com.ibm.wala.ssa.SSAGetInstruction)
     */
    public void visitGet(SSAGetInstruction instruction) {
      if (!instruction.isStatic()) {
        final int ssaVar = instruction.getRef();
        noIdentity = true;
        transfer1 = NullPointerState.denullifyFunction(ssaVar);
        transfer2 = NullPointerState.nullifyFunction(ssaVar);
      } else {
        noIdentity = false;
        transfer1 = NullPointerState.identityFunction();
        transfer2 = NullPointerState.identityFunction();
      }
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGetCaughtException(com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction)
     */
    public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGoto(com.ibm.wala.ssa.SSAGotoInstruction)
     */
    public void visitGoto(SSAGotoInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitInstanceof(com.ibm.wala.ssa.SSAInstanceofInstruction)
     */
    public void visitInstanceof(SSAInstanceofInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitInvoke(com.ibm.wala.ssa.SSAInvokeInstruction)
     */
    public void visitInvoke(SSAInvokeInstruction instruction) {
      if (!instruction.isStatic()) {
        // when no exception is raised on a virtual call, the receiver is not null. Otherwise it is
        // unsure if the receiver is definitely null as an exception may also stem from the method itself.
        noIdentity = true;
        transfer1 = NullPointerState.denullifyFunction(instruction.getReceiver());
        transfer2 = NullPointerState.identityFunction();
      } else {
        noIdentity = false;
        transfer1 = NullPointerState.identityFunction();
        transfer2 = NullPointerState.identityFunction();
      }
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitLoadMetadata(com.ibm.wala.ssa.SSALoadMetadataInstruction)
     */
    public void visitLoadMetadata(SSALoadMetadataInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitMonitor(com.ibm.wala.ssa.SSAMonitorInstruction)
     */
    public void visitMonitor(SSAMonitorInstruction instruction) {
      // when no exception is raised on a synchronized statement, the monitor is not null. Otherwise it is
      // unsure if the monitor is definitely null as other exception may also appear (synchronization related).
      noIdentity = true;
      transfer1 = NullPointerState.denullifyFunction(instruction.getRef());
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitNew(com.ibm.wala.ssa.SSANewInstruction)
     */
    public void visitNew(SSANewInstruction instruction) {
      /*
       * If an exception is raised upon new is called, then the defined variable
       * is null else it is guaranteed to not be null.
       */
      noIdentity = true;
      transfer1 = NullPointerState.denullifyFunction(instruction.getDef());
      transfer2 = NullPointerState.nullifyFunction(instruction.getDef());
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPhi(com.ibm.wala.ssa.SSAPhiInstruction)
     */
    public void visitPhi(SSAPhiInstruction instruction) {
      noIdentity = true;
      int[] uses = new int[instruction.getNumberOfUses()];
      for (int i = 0; i < uses.length; i++) {
        uses[i] = instruction.getUse(i);
      }
      
      transfer1 = NullPointerState.phiValueMeetFunction(instruction.getDef(), uses);
      // should not be used as no alternative path exists
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPi(com.ibm.wala.ssa.SSAPiInstruction)
     */
    public void visitPi(SSAPiInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPut(com.ibm.wala.ssa.SSAPutInstruction)
     */
    public void visitPut(SSAPutInstruction instruction) {
      if (!instruction.isStatic()) {
        final int ssaVar = instruction.getRef();
        noIdentity = true;
        transfer1 = NullPointerState.denullifyFunction(ssaVar);
        transfer2 = NullPointerState.nullifyFunction(ssaVar);
      } else {
        noIdentity = false;
        transfer1 = NullPointerState.identityFunction();
        transfer2 = NullPointerState.identityFunction();
      }
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitReturn(com.ibm.wala.ssa.SSAReturnInstruction)
     */
    public void visitReturn(SSAReturnInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitSwitch(com.ibm.wala.ssa.SSASwitchInstruction)
     */
    public void visitSwitch(SSASwitchInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitThrow(com.ibm.wala.ssa.SSAThrowInstruction)
     */
    public void visitThrow(SSAThrowInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }

    /* (non-Javadoc)
     * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitUnaryOp(com.ibm.wala.ssa.SSAUnaryOpInstruction)
     */
    public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
      noIdentity = false;
      transfer1 = NullPointerState.identityFunction();
      transfer2 = NullPointerState.identityFunction();
    }
    
  }
  
}

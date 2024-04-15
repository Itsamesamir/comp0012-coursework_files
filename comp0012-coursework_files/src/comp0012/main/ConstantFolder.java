package comp0012.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.util.InstructionFinder;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.TargetLostException;
import org.apache.bcel.generic.*;



public class ConstantFolder
{
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	public ConstantFolder(String classFilePath)
	{
		try{
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch(IOException e){
			e.printStackTrace();
		}
	}

	public void optimize()
	{
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = cgen.getConstantPool();

		// Implement your optimization here

		Method[] methods = cgen.getMethods();
		for (Method m : methods) {
			MethodGen mg = new MethodGen(m, cgen.getClassName(), cpgen);
			InstructionList il = mg.getInstructionList();
			if (il == null) {
				continue;
			}

			InstructionFinder f = new InstructionFinder(il);
			boolean changed;
			do
			{
				//variable used to check if any optimizaation change has been made
				changed=false;
				//search for pattern of a arithmetic instruction
				for(Iterator<InstructionHandle[]> i = f.search("(LDC|LDC2_W)(LDC|LDC2_W)(IADD|ISUB|IMUL|IDIV|LADD|LSUB|LMUL|LDIV|DADD|DSUB|DMUL|DDIV|FADD|FSUB|FMUL|FDIV)"); i.hasNext();){
					InstructionHandle[] match = (InstructionHandle[]) i.next();
					InstructionHandle first = match[0];
					InstructionHandle second = match[1];
					InstructionHandle third = match[2];
					
					
					Number res = calc(first, second, third,cpgen);
					if(res != null){
						InstructionHandle newInstruct = null;
						//insert single load instruction before the older 3 instructions
						if (res instanceof Integer)
							newInstruct = il.insert(first,  new LDC(cpgen.addInteger(res.intValue())));
						else if (res instanceof Long)
							newInstruct = il.insert(first,  new LDC2_W(cpgen.addLong(res.longValue())));
						else if (res instanceof Float)
							newInstruct = il.insert(first,  new LDC(cpgen.addFloat(res.floatValue())));
						else if (res instanceof Double)
							newInstruct = il.insert(first,  new LDC2_W(cpgen.addDouble(res.doubleValue())));

						try{
							if(newInstruct != null){
								//new change has been made and delete the older 3 instructions
								il.delete(first, third);
								changed = true;
							}
						}catch(TargetLostException e){
							
							e.printStackTrace();
						}
						
					}

				}
				//search for a pattern of comparison instructions
				for(Iterator<InstructionHandle[]> i = f.search("(LDC|LDC2_W)(LDC|LDC2_W)(IF_ICMPEQ|IF_ICMPNE|IF_ICMPLT|IF_ICMPGE|IF_ICMPGT|IF_ICMPLE|LCMP|FCMPG|FCMPL|DCMPG|DCMPL)"); i.hasNext();){
					InstructionHandle[] match = (InstructionHandle[]) i.next();
					InstructionHandle firstc = match[0];
					InstructionHandle secondc = match[1];
					InstructionHandle thirdc = match[2];
					
					
					Boolean resc = calcComparison(firstc, secondc, thirdc,cpgen);
					if(resc != null){
						InstructionHandle newInstruct = null;
						
						newInstruct = il.insert(firstc,resc? new ICONST(1): new ICONST(0));
						try{
							if(newInstruct != null){
								il.delete(firstc, thirdc);
								
							}
						}catch(TargetLostException e){
							
							e.printStackTrace();
						}
						
					}

				}
				//dead code elimination
				// Iterate to find dead code patterns
				changed = false;
				for (Iterator<InstructionHandle[]> i = f.search("(return|ireturn|lreturn|freturn|dreturn|areturn)"); i.hasNext(); ) {
					// Get matched dead code
					InstructionHandle[] deadCodeMatch = (InstructionHandle[]) i.next();
					InstructionHandle current = deadCodeMatch[0]; //this is the return instruction
					InstructionHandle next = current.getNext();
					List<InstructionHandle> ihList = new ArrayList<>();

					while (next != null) {
						ihList.add(next);
						next = next.getNext(); // Move to the next instruction
					}

					try{
						for (InstructionHandle toDelete : ihList){
							il.delete(toDelete);
						}
						changed = true;
					}catch(TargetLostException e){
						// Auto-generated catch block
						e.printStackTrace();
					}
				}

				// Remove empty blocks
				changed = false;
				for (Iterator<InstructionHandle[]> i = f.search("goto|goto_w"); i.hasNext(); ) {
					InstructionHandle[] emptyBlockMatch = (InstructionHandle[]) i.next();
					InstructionHandle current = emptyBlockMatch[0];

					if (current.getInstruction() instanceof GOTO gotoInstruction) {
                        InstructionHandle targetHandle = gotoInstruction.getTarget();
						//targetHandle points to the target of the GOTO instruction.
						if (targetHandle.getInstruction() instanceof GOTO || targetHandle.getInstruction() instanceof NOP) {
							// Remove the current GOTO instruction since it leads to an empty block
							try {
								il.delete(current);
								changed = true;
							} catch (TargetLostException e) {
								// Handle the case when deleting the instruction causes other target handles to be lost
								e.printStackTrace();
							}
						}
					}
				}

				changed = false;
				//algebraic simplifications
				// handle any 0s in + and -
				for (Iterator<InstructionHandle[]> i = f.search("(LDC|LDC2_W)(LDC|LDC2_W)(IADD|ISUB|LADD|LSUB|DADD|DSUB|FADD|FSUB)"); i.hasNext();){
					InstructionHandle [] match = (InstructionHandle[]) i.next();
					InstructionHandle first = match[0];
					InstructionHandle second = match[1];
					InstructionHandle third = match[2];

					Number res = 0;
					if (first.getInstruction() instanceof LDC) {
						LDC ldcInstruction = (LDC) first.getInstruction();
						Object value = ldcInstruction.getValue(cpgen);
						if (value instanceof Number && ((Number) value).intValue() == 0) {
							// If the operation is addition/subtraction and the first operand is 0,
							// we can replace the operation with the second operand directly.
							try{
								InstructionHandle newInstruct = il.insert(first, second.getInstruction().copy());
								il.delete(first, third);
								changed = true;
							}catch(TargetLostException e){
								e.printStackTrace();
							}
						}
					}
					if (second.getInstruction() instanceof LDC) {
						LDC ldcInstruction2 = (LDC) second.getInstruction();
						Object value = ldcInstruction2.getValue(cpgen);
						if (value instanceof Number && ((Number) value).intValue() == 0) {
							//same but we replace it with the first operand this time
							try{
								InstructionHandle newInstruct = il.insert(first, first.getInstruction().copy());
								il.delete(first, third);
								changed = true;
							}catch(TargetLostException e){
								e.printStackTrace();
							}
						}
					}

				}
				//handle any 1s in multiplication and division
				for (Iterator<InstructionHandle[]> i = f.search("(LDC|LDC2_W)(LDC|LDC2_W)(IMUL|IDIV|LMUL|LDIV|DMUL|DDIV|FMUL|FDIV)"); i.hasNext();){
					InstructionHandle [] match = (InstructionHandle[]) i.next();
					InstructionHandle first = match[0];
					InstructionHandle second = match[1];
					InstructionHandle third = match[2];

					if (first.getInstruction() instanceof LDC) {
						LDC ldcInstruction = (LDC) first.getInstruction();
						Object value = ldcInstruction.getValue(cpgen);
						if (value instanceof Number && ((Number) value).intValue() == 1) {
							// If the operation is multiplication/division and the first operand is 1,
							// we can replace the operation with the second operand directly.
							try{
								InstructionHandle newInstruct = il.insert(first, second.getInstruction().copy());
								il.delete(first, third);
								changed = true;
							}catch(TargetLostException e){
								e.printStackTrace();
							}
						}
					}
					if (second.getInstruction() instanceof LDC) {
						LDC ldcInstruction2 = (LDC) second.getInstruction();
						Object value = ldcInstruction2.getValue(cpgen);
						if (value instanceof Number && ((Number) value).intValue() == 1) {
							//same but we replace it with the first operand this time
							try{
								InstructionHandle newInstruct = il.insert(first, first.getInstruction().copy());
								il.delete(first, third);
								changed = true;
							}catch(TargetLostException e){
								e.printStackTrace();
							}
						}
					}
				}
				for (Iterator<InstructionHandle[]> j = f.search("(ISTORE|LSTORE|FSTORE|DSTORE) LDC"); j
							.hasNext();) {
						InstructionHandle[] currentMatch = j.next();
						InstructionHandle storeInstruct = currentMatch[0];
						InstructionHandle ldcInstruct = currentMatch[1];

						// get the value of the constant
						int localIndex = ((StoreInstruction) storeInstruct.getInstruction()).getIndex();
						Number constantValue = (Number) ((LDC) ldcInstruct.getInstruction()).getValue(cpgen);

						// find where the interval ends for current variable assignment
						InstructionHandle endOfInterval = findEndInterval(storeInstruct.getNext(), localIndex, il);

						// replace the access of variable in the interval with the value
						replaceVariableAccesses(storeInstruct.getNext(), endOfInterval, localIndex, constantValue, il,
								cpgen);
						changed = true;

						// delete the variable assignment and ldc instruction
						try {
							il.delete(storeInstruct, endOfInterval);
						} catch (TargetLostException e) {
							e.printStackTrace();
						}
					}
				//might try to do some strength reduction


			} while(changed);

			il.setPositions(true);
			mg.setMaxStack();
			mg.setMaxLocals();
			cgen.replaceMethod(m, mg.getMethod());
		}

		this.optimized = cgen.getJavaClass();
	}


	private Number calc (InstructionHandle first, InstructionHandle second, InstructionHandle third, ConstantPoolGen cpgen){
		Number firstValue = null;
		Number secondValue = null;
		if(first.getInstruction() instanceof LDC){
			firstValue = (Number)((LDC)first.getInstruction()).getValue(cpgen);
			
		}
		else if(first.getInstruction() instanceof LDC2_W){

			firstValue = (Number)((LDC2_W)first.getInstruction()).getValue(cpgen);
			
		}
		if(second.getInstruction() instanceof LDC){
			secondValue = (Number)((LDC)second.getInstruction()).getValue(cpgen);
			
		}
		else if (second.getInstruction() instanceof LDC2_W){
			secondValue = (Number)((LDC2_W)second.getInstruction()).getValue(cpgen);
			
		}
		if(firstValue == null || secondValue == null){

			return null;
		}
		Number result = null;
		//calculate the actual arithmetic operation
		if(firstValue instanceof Integer && secondValue instanceof Integer){
			if(third.getInstruction() instanceof IADD){
				result = firstValue.intValue() + secondValue.intValue();
			}
			else if(third.getInstruction() instanceof ISUB){
				result = firstValue.intValue() - secondValue.intValue();
			}
			else if(third.getInstruction() instanceof IMUL){
				result = firstValue.intValue() * secondValue.intValue();
			}
			else if(third.getInstruction() instanceof IDIV){
				result = firstValue.intValue() / secondValue.intValue();
			}
		}
		else if(firstValue instanceof Long && secondValue instanceof Long){
			if(third.getInstruction() instanceof LADD){
				result = firstValue.intValue() + secondValue.longValue();
			}
			else if(third.getInstruction() instanceof LSUB){
				result = firstValue.intValue() - secondValue.longValue();
			}
			else if(third.getInstruction() instanceof LMUL){
				result = firstValue.intValue() * secondValue.longValue();
			}
			else if(third.getInstruction() instanceof LDIV){
				result = firstValue.intValue() / secondValue.longValue();
			}
		}
		else if(firstValue instanceof Float && secondValue instanceof Float){
			if(third.getInstruction() instanceof FADD){
				result = firstValue.intValue() + secondValue.floatValue();
			}
			else if(third.getInstruction() instanceof FSUB){
				result = firstValue.intValue() - secondValue.floatValue();
			}
			else if(third.getInstruction() instanceof FMUL){
				result = firstValue.intValue() * secondValue.floatValue();
			}
			else if(third.getInstruction() instanceof FDIV){
				result = firstValue.intValue() / secondValue.floatValue();
			}
		}
		else if(firstValue instanceof Double && secondValue instanceof Double){
			if(third.getInstruction() instanceof DADD){
				result = firstValue.intValue() + secondValue.doubleValue();
			}
			else if(third.getInstruction() instanceof DSUB){
				result = firstValue.intValue() - secondValue.doubleValue();
			}
			else if(third.getInstruction() instanceof DMUL){
				result = firstValue.intValue() * secondValue.doubleValue();
			}
			else if(third.getInstruction() instanceof DDIV){
				result = firstValue.intValue() / secondValue.doubleValue();
			}
		}
		else{
			return null;
		}
		//System.out.println(result);
		return result;
	}

	private Boolean calcComparison(InstructionHandle first, InstructionHandle second, InstructionHandle operation, ConstantPoolGen cpgen) {
		Number firstValue = null;
		Number secondValue = null;
		int comparisonResult ;
		if(first.getInstruction() instanceof LDC){
			firstValue = (Number)((LDC)first.getInstruction()).getValue(cpgen);
			
		}
		else if(first.getInstruction() instanceof LDC2_W){

			firstValue = (Number)((LDC2_W)first.getInstruction()).getValue(cpgen);
			
		}
		if(second.getInstruction() instanceof LDC){
			secondValue = (Number)((LDC)second.getInstruction()).getValue(cpgen);
			
		}
		else if (second.getInstruction() instanceof LDC2_W){
			secondValue = (Number)((LDC2_W)second.getInstruction()).getValue(cpgen);
			
		}

		if (firstValue == null || secondValue == null) return null;
		if (!(firstValue instanceof Number) || !(secondValue instanceof Number)) return null;

		Instruction instr = operation.getInstruction();
		Number fNum = (Number)firstValue;
		Number sNum = (Number)secondValue;

		if (instr instanceof IF_ICMPEQ) {
			return fNum.intValue() == sNum.intValue();
		} else if (instr instanceof IF_ICMPNE) {
			return fNum.intValue() != sNum.intValue();
		} else if (instr instanceof IF_ICMPLT) {
			return fNum.intValue() < sNum.intValue();
		} else if (instr instanceof IF_ICMPGE) {
			return fNum.intValue() >= sNum.intValue();
		} else if (instr instanceof IF_ICMPGT) {
			
			return fNum.intValue() > sNum.intValue();
		} else if (instr instanceof IF_ICMPLE) {
			return fNum.intValue() <= sNum.intValue();
	
		}else if (instr instanceof LCMP) {
			comparisonResult = Long.compare(fNum.longValue(), sNum.longValue());
			return comparisonResult == 0 ? true : comparisonResult > 0;
		} else if (instr instanceof FCMPG) {
			comparisonResult = Float.compare(fNum.floatValue(), sNum.floatValue());
			return comparisonResult == 0 ? true : comparisonResult > 0;
			
		}else if (instr instanceof FCMPL) {
			comparisonResult = Float.compare(fNum.floatValue(), sNum.floatValue());
			return comparisonResult == 0 ? true : comparisonResult < 0;
			
		}  else if (instr instanceof DCMPG) {
			comparisonResult = Double.compare(fNum.doubleValue(), sNum.doubleValue());
			return comparisonResult == 0 ? true : comparisonResult > 0;
		}else if (instr instanceof FCMPG || instr instanceof DCMPL) {
			comparisonResult = Float.compare(fNum.floatValue(), sNum.floatValue());
			return comparisonResult == 0 ? true : comparisonResult < 0;
			
		} 
	
		return null;
	
	}

	private InstructionHandle findEndInterval(InstructionHandle start, int localVarIndex, InstructionList il) {

		InstructionHandle current = start;
		// Iterate through the instructions until the end of the method or until a new
		// assignment to the variable
		while (current != null && !isVariableAssignment(current, localVarIndex)) {
			current = current.getNext();
		}
		// If a new assignment was found, set the end of the interval to the previous
		// instruction
		// Otherwise, set it to the end of the method
		if (current != null) {
			return current.getPrev();
		} else {
			return il.getEnd();
		}
	}

	private boolean isVariableAssignment(InstructionHandle instruction, int localVarIndex) {
		return (instruction.getInstruction() instanceof StoreInstruction
				&& ((StoreInstruction) instruction.getInstruction()).getIndex() == localVarIndex);
	}

	private void replaceVariableAccesses(InstructionHandle start, InstructionHandle end, int localVarIndex,
			Number constantValue, InstructionList il, ConstantPoolGen cpgen) {
		InstructionHandle current = start;
		while (current != end.getNext()) {
			Instruction instruction = current.getInstruction();
			if (instruction instanceof LoadInstruction && ((LoadInstruction) instruction).getIndex() == localVarIndex) {
				// Determine the type of constant value and add it to the constant pool
				// accordingly
				if (constantValue instanceof Integer) {
					il.insert(current,  new LDC(cpgen.addInteger(constantValue.intValue())));
				} else if (constantValue instanceof Long) {
					long longValue = constantValue.longValue();
					if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) longValue));
					} else {
						// Otherwise, load as long
						il.insert(current,  new LDC2_W(cpgen.addLong(constantValue.longValue())));
					}
				} else if (constantValue instanceof Float) {
					float floatValue = constantValue.floatValue();
					// Check if the float value is within the range of an int
					if (floatValue >= Integer.MIN_VALUE && floatValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) floatValue));
					} else {
						// Otherwise, load as float
						il.insert(current,  new LDC(cpgen.addFloat(constantValue.floatValue())));
					}
				} else if (constantValue instanceof Double) {
					double doubleValue = constantValue.doubleValue();
					// Check if the double value is within the range of an int
					if (doubleValue >= Integer.MIN_VALUE && doubleValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) doubleValue));
					} else {
						// Otherwise, load as double
						il.insert(current,  new LDC2_W(cpgen.addDouble(constantValue.doubleValue())));
					}
				}
				// try {
				// 	il.delete(current);
				// } catch (TargetLostException e) {
				// 	e.printStackTrace();
				// }
			}
			current = current.getNext();
		}
	}
	public void write(String optimisedFilePath)
	{
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}
}
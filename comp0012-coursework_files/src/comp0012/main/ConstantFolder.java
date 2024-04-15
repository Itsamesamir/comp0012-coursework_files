package comp0012.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.util.InstructionFinder;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.TargetLostException;
import org.apache.bcel.generic.*;

public class ConstantFolder {
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	public ConstantFolder(String classFilePath) {
		try {
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void optimize() {
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = cgen.getConstantPool();
		// Simple folding
		Method[] methods = cgen.getMethods();
		for (Method m : methods) {
			MethodGen mg = new MethodGen(m, cgen.getClassName(), cpgen);
			InstructionList il = mg.getInstructionList();
			if (il == null) {
				continue;
			}
			InstructionFinder f = new InstructionFinder(il);
			boolean changed;
			do {
				changed = false;
				for (Iterator i = f.search(
						"(LDC|LDC2_W)(LDC|LDC2_W)(IADD|ISUB|IMUL|IDIV|LADD|LSUB|LMUL|LDIV|DADD|DSUB|DMUL|DDIV|FADD|FSUB|FMUL|FDIV)"); i
								.hasNext();) {
					InstructionHandle[] match = (InstructionHandle[]) i.next();
					InstructionHandle first = match[0];
					InstructionHandle second = match[1];
					InstructionHandle third = match[2];

					Number res = calc(first, second, third, cpgen);
					if (res != null) {
						// InstructionList newIL = new InstructionList();
						InstructionHandle newInstruct = null;
						// newInstruct = il.append(third, new LDC(cpgen.addInteger(res.intValue())));
						// newIL.append(new LDC(cpgen.addInteger(res.intValue())));
						if (res instanceof Integer)
							newInstruct = il.insert(first, new LDC(cpgen.addInteger(res.intValue())));
						else if (res instanceof Long)
							newInstruct = il.insert(first, new LDC2_W(cpgen.addLong(res.longValue())));
						else if (res instanceof Float)
							newInstruct = il.insert(first, new LDC(cpgen.addFloat(res.floatValue())));
						else if (res instanceof Double)
							newInstruct = il.insert(first, new LDC2_W(cpgen.addDouble(res.doubleValue())));

						// newInstruct = il.insert(first, new LDC(cpgen.addInteger(res.intValue())));
						try {
							if (newInstruct != null) {
								il.delete(first, third);
								changed = true;
							}
						} catch (TargetLostException e) {
							// Auto-generated catch block
							e.printStackTrace();
						}
						// il.delete(first, third);

					}

					// task 3

					// loop through instructions to find any variable assignments
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
								cgen);
						changed = true;

						// delete the variable assignment and ldc instruction
						try {
							il.delete(storeInstruct, endOfInterval);
						} catch (TargetLostException e) {
							e.printStackTrace();
						}
					}

				}
			} while (changed);

			il.setPositions(true);
			mg.setMaxStack();
			mg.setMaxLocals();
			cgen.replaceMethod(m, mg.getMethod());
		}

		// Implement your optimization here

		this.optimized = cgen.getJavaClass();
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
			Number constantValue, InstructionList il) {
		InstructionHandle current = start;
		while (current != end.getNext()) {
			Instruction instruction = current.getInstruction();
			if (instruction instanceof LoadInstruction && ((LoadInstruction) instruction).getIndex() == localVarIndex) {
				// Determine the type of constant value and add it to the constant pool
				// accordingly
				if (constantValue instanceof Integer) {
					il.insert(current, new LDC(constantValue.intValue()));
				} else if (constantValue instanceof Long) {
					long longValue = constantValue.longValue();
					if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) longValue));
					} else {
						// Otherwise, load as long
						il.insert(current, new LDC2_W(longValue));
					}
				} else if (constantValue instanceof Float) {
					float floatValue = constantValue.floatValue();
					// Check if the float value is within the range of an int
					if (floatValue >= Integer.MIN_VALUE && floatValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) floatValue));
					} else {
						// Otherwise, load as float
						il.insert(current, new LDC(constantValue.floatValue()));
					}
				} else if (constantValue instanceof Double) {
					double doubleValue = constantValue.doubleValue();
					// Check if the double value is within the range of an int
					if (doubleValue >= Integer.MIN_VALUE && doubleValue <= Integer.MAX_VALUE) {
						// Load as int if it fits within the range
						il.insert(current, new LDC((int) doubleValue));
					} else {
						// Otherwise, load as double
						il.insert(current, new LDC2_W(doubleValue));
					}
				}
				try {
					il.delete(current);
				} catch (TargetLostException e) {
					e.printStackTrace();
				}
			}
			current = current.getNext();
		}
	}

	private Number calc(InstructionHandle first, InstructionHandle second, InstructionHandle third,
			ConstantPoolGen cpgen) {
		Number firstValue = null;
		Number secondValue = null;
		if (first.getInstruction() instanceof LDC) {
			firstValue = (Number) ((LDC) first.getInstruction()).getValue(cpgen);
			// System.out.println(firstValue);
		} else if (first.getInstruction() instanceof LDC2_W) {

			firstValue = (Number) ((LDC2_W) first.getInstruction()).getValue(cpgen);
			// System.out.println("1x");
		} else {
			// System.out.println("x");
		}
		if (second.getInstruction() instanceof LDC) {
			secondValue = (Number) ((LDC) second.getInstruction()).getValue(cpgen);
			// System.out.println("2");
		} else if (second.getInstruction() instanceof LDC2_W) {
			secondValue = (Number) ((LDC2_W) second.getInstruction()).getValue(cpgen);
			// System.out.println("2x");
		} else {
			// System.out.println("y");
		}

		// System.out.println(first.getInstruction() );

		if (firstValue == null || secondValue == null) {

			return null;
		}
		Number result = null;
		if (firstValue instanceof Integer && secondValue instanceof Integer) {
			if (third.getInstruction() instanceof IADD) {
				result = firstValue.intValue() + secondValue.intValue();
			} else if (third.getInstruction() instanceof ISUB) {
				result = firstValue.intValue() - secondValue.intValue();
			} else if (third.getInstruction() instanceof IMUL) {
				result = firstValue.intValue() * secondValue.intValue();
			} else if (third.getInstruction() instanceof IDIV) {
				result = firstValue.intValue() / secondValue.intValue();
			}
		} else if (firstValue instanceof Long && secondValue instanceof Long) {
			if (third.getInstruction() instanceof LADD) {
				result = firstValue.intValue() + secondValue.longValue();
			} else if (third.getInstruction() instanceof LSUB) {
				result = firstValue.intValue() - secondValue.longValue();
			} else if (third.getInstruction() instanceof LMUL) {
				result = firstValue.intValue() * secondValue.longValue();
			} else if (third.getInstruction() instanceof LDIV) {
				result = firstValue.intValue() / secondValue.longValue();
			}
		} else if (firstValue instanceof Float && secondValue instanceof Float) {
			if (third.getInstruction() instanceof FADD) {
				result = firstValue.intValue() + secondValue.floatValue();
			} else if (third.getInstruction() instanceof FSUB) {
				result = firstValue.intValue() - secondValue.floatValue();
			} else if (third.getInstruction() instanceof FMUL) {
				result = firstValue.intValue() * secondValue.floatValue();
			} else if (third.getInstruction() instanceof FDIV) {
				result = firstValue.intValue() / secondValue.floatValue();
			}
		} else if (firstValue instanceof Double && secondValue instanceof Double) {
			if (third.getInstruction() instanceof DADD) {
				result = firstValue.intValue() + secondValue.doubleValue();
			} else if (third.getInstruction() instanceof DSUB) {
				result = firstValue.intValue() - secondValue.doubleValue();
			} else if (third.getInstruction() instanceof DMUL) {
				result = firstValue.intValue() * secondValue.doubleValue();
			} else if (third.getInstruction() instanceof DDIV) {
				result = firstValue.intValue() / secondValue.doubleValue();
			}
		} else {
			return null;
		}
		// System.out.println(result);
		return result;
	}

	public void write(String optimisedFilePath) {
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
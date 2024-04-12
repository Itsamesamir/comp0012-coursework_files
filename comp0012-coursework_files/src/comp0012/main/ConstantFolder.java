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
				changed=false;
				for(Iterator<InstructionHandle[]> i = f.search("(LDC|LDC2_W)(LDC|LDC2_W)(IADD|ISUB|IMUL|IDIV|LADD|LSUB|LMUL|LDIV|DADD|DSUB|DMUL|DDIV|FADD|FSUB|FMUL|FDIV)"); i.hasNext();){
					InstructionHandle[] match = (InstructionHandle[]) i.next();
					InstructionHandle first = match[0];
					InstructionHandle second = match[1];
					InstructionHandle third = match[2];

					//Simple folding
					Number res = calc(first, second, third,cpgen);
					if(res != null){
						InstructionHandle newInstruct = null;
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
								il.delete(first, third);
								//changed = true;
							}
						}catch(TargetLostException e){
							// Auto-generated catch block
							e.printStackTrace();
						}
						//il.delete(first, third);
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

				//might try to do some strength reduction
				changed = false;
				for (Iterator<InstructionHandle[]> it = f.search("(LDC|LDC2_W) IMUL"); it.hasNext(); ) {
					InstructionHandle[] match = it.next();
					InstructionHandle first = match[0];  // LDC or LDC2_W instruction handle
					InstructionHandle second = match[1]; // IMUL instruction handle

					if (first.getInstruction() instanceof LDC) {
						LDC ldc = (LDC) first.getInstruction();
						Object value = ldc.getValue(cpgen);
						if (value instanceof Integer) {
							int intValue = (Integer) value;
							if (Integer.bitCount(intValue) == 1) { // Check if it's a power of 2
								int shift = Integer.numberOfTrailingZeros(intValue);
								// Assuming the multiplication's result is used just after this, and we replace it with shift
								il.append(new BIPUSH((byte)shift)); // Load the shift amount onto the stack
								il.append(new ISHL()); // Apply left shift
								try {
									il.delete(first, second);
									changed = true;// Remove the multiplication instruction
								} catch (TargetLostException e) {
									e.printStackTrace();
								}
							}
						}
					}
				}


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
			//System.out.println(firstValue);
		}
		else if(first.getInstruction() instanceof LDC2_W){

			firstValue = (Number)((LDC2_W)first.getInstruction()).getValue(cpgen);
			//System.out.println("1x");
		}
		else{
			//System.out.println("x");
		}
		if(second.getInstruction() instanceof LDC){
			secondValue = (Number)((LDC)second.getInstruction()).getValue(cpgen);
			//System.out.println("2");
		}
		else if (second.getInstruction() instanceof LDC2_W){
			secondValue = (Number)((LDC2_W)second.getInstruction()).getValue(cpgen);
			//System.out.println("2x");
		}
		else{
			//System.out.println("y");
		}

		//System.out.println(first.getInstruction() );


		if(firstValue == null || secondValue == null){

			return null;
		}
		Number result = null;
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
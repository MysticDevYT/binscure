package cookiedragon.obfuscator.processors.indirection

import cookiedragon.obfuscator.CObfuscator
import cookiedragon.obfuscator.IClassProcessor
import cookiedragon.obfuscator.classpath.ClassPath
import cookiedragon.obfuscator.kotlin.*
import cookiedragon.obfuscator.processors.renaming.impl.ClassRenamer
import cookiedragon.obfuscator.utils.*
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import kotlin.properties.Delegates

/**
 * @author cookiedragon234 22/Jan/2020
 */
object DynamicCallObfuscation: IClassProcessor {
	val targetOps = arrayOf(INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE)
	
	val debugName = "execute"
	
	var classVersion by Delegates.notNull<Int>()
	var isInit: Boolean = false
	val decryptNode: ClassNode by lazy {
		isInit = true
		ClassNode().apply {
			access = ACC_PUBLIC + ACC_FINAL
			version = classVersion
			name = ClassRenamer.namer.uniqueRandomString()
			signature = null
			superName = "java/lang/Object"
		}
	}
	
	val stringDecryptMethod: MethodNode by lazy {
		MethodNode(
			ACC_PRIVATE + ACC_STATIC,
			"a",
			"(Ljava/lang/String;)Ljava/lang/String;",
			null,
			null
		).apply {
			generateDecryptorMethod(decryptNode, this)
			decryptNode.methods.add(this)
		}
	}
	
	val bootStrapMethod: MethodNode by lazy {
		MethodNode(
			ACC_PUBLIC + ACC_STATIC,
			"b",
			"(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
			null,
			null
		).apply {
			generateBootstrapMethod(decryptNode.name, stringDecryptMethod, this)
			decryptNode.methods.add(this)
		}
	}
	
	val handler: Handle by lazy {
		Handle(H_INVOKESTATIC, decryptNode.name, bootStrapMethod.name, bootStrapMethod.desc, false)
	}
	
	override fun process(classes: MutableCollection<ClassNode>, passThrough: MutableMap<String, ByteArray>) {
		//if (!ConfigurationManager.rootConfig.indirection.enabled)
		//	return
		
		classVersion = if (classes.isEmpty()) V1_7 else classes.first().version
		
		for (classNode in ArrayList(classes)) {
			if (CObfuscator.isExcluded(classNode))
				continue
			
			for (method in classNode.methods) {
				if (CObfuscator.isExcluded(classNode, method) || CObfuscator.noMethodInsns(method))
					continue
				
				val outInsns = InsnList().apply {
					for (insn in method.instructions) {
						if (insn is MethodInsnNode) {
							if (targetOps.contains(insn.opcode)) {
								if (method.name == debugName) {
									println("---- ${method.name}")
									println("b4 ${method.instructions.toOpcodeStrings()}")
									println("target: ${insn.opcodeString()}")
								}
								
								var newDesc = insn.desc
								if (insn.opcode != INVOKESTATIC) {
									newDesc = newDesc.replace("(", "(L${insn.owner};")
								}
								val returnType = Type.getReturnType(newDesc)
								
								// Downcast types to java/lang/Object
								val args = Type.getArgumentTypes(newDesc)
								for (i in args.indices) {
									args[i] = genericType(args[i])
								}
								
								newDesc = Type.getMethodDescriptor(genericType(returnType), *args)
								
								val indyNode = InvokeDynamicInsnNode(
									"bob",
									newDesc,
									handler,
									insn.opcode,
									encryptName(classNode, method, insn.owner.replace('/', '.')),
									encryptName(classNode, method, insn.name),
									encryptName(classNode, method, insn.desc)
								)
								add(indyNode)
								if (method.name == debugName) {
									println("Replacement: ${indyNode.opcodeString()}")
								}
								
								var checkCast: TypeInsnNode? = if (returnType.sort == Type.OBJECT || returnType.sort == Type.ARRAY)
									TypeInsnNode(CHECKCAST, returnType.internalName)
								else null
								
								
								/*null
								if (returnType.sort == Type.ARRAY) {
									checkCast = (TypeInsnNode(CHECKCAST, returnType.internalName))
								} else if (returnType.sort == Type.OBJECT) {
									if (insn.next is MethodInsnNode) {
										val next = insn.next as MethodInsnNode
										val params = Type.getArgumentTypes(next.desc)
										if (params.isEmpty()) {
											if (insn.next.opcode == INVOKEVIRTUAL) {
												checkCast = (TypeInsnNode(CHECKCAST, next.owner))
											}
										} else {
											checkCast = (TypeInsnNode(CHECKCAST, params.last().internalName))
										}
									} else if (arrayOf(POP, POP2, RETURN, IFNONNULL, IFNULL).contains(insn.next?.opcode)) {
										//
									} else {
										checkCast = (TypeInsnNode(CHECKCAST, returnType.internalName))
									}
								}*/
								if (checkCast != null) {
									if (checkCast.desc != Any::class.internalName) {
										add(checkCast)
									}
								}
								continue
							}
						}
						add(insn)
					}
					method.instructions = this
					if (method.name == debugName) {
						println("after ${method.instructions.toOpcodeStrings()}")
					}
				}
			}
		}
		
		if (isInit) {
			ClassVerifier.verifyClass(decryptNode)
			verifyClass(decryptNode)
			classes.add(decryptNode)
			ClassPath.classes[decryptNode.name] = decryptNode
			ClassPath.classPath[decryptNode.name] = decryptNode
		}
	}
	
	private fun genericType(type: Type): Type {
		return when (type.sort) {
			Type.OBJECT -> Type.getType(Any::class.java)
			else -> type
		}
	}
	
	private fun encryptName(classNode: ClassNode, methodNode: MethodNode, originalStr: String): String {
		val classHash = classNode.name.replace('/', '.').hashCode()
		val methodHash = methodNode.name.replace('/', '.').hashCode()
		
		val original = originalStr.toCharArray()
		val new = CharArray(original.size)
		
		for (i in original.indices) {
			val char = original[i]
			new[i] = when (i % 5) {
				0 -> char xor 2
				1 -> char xor classHash
				2 -> char xor methodHash
				3 -> char xor (classHash + methodHash)
				4 -> char xor i
				else -> throw IllegalStateException("Illegal ${i % 6}")
			}
		}
		return String(new)
	}
	
	data class MethodCall(val classNode: ClassNode, val methodNode: MethodNode, val insnNode: MethodInsnNode)
}

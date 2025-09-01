package com.ammaraskar.intent.fuzz

import jadx.core.dex.instructions.BaseInvokeNode
import jadx.core.dex.instructions.ConstStringNode
import jadx.core.dex.instructions.args.InsnWrapArg
import jadx.core.dex.instructions.args.RegisterArg
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.visitors.AbstractVisitor

class IntentExtraUsageTreeVisitor : AbstractVisitor() {

    /**
     * Mapping of intent extra keys to their types.
     */
    val extras: HashMap<String, String> = hashMapOf()

    // val extras: MutableList<String> = mutableListOf()

    override fun visit(mth: MethodNode) {
        if (mth.isNoCode) {
            return;
        }

        for (basicBlock in mth.basicBlocks) {
            for (instr in basicBlock.instructions) {
                if (instr is BaseInvokeNode) {
                    this.visitInvokeNode(instr)
                }
            }
        }
    }

    private val gettersToExtraTypes = hashMapOf(
        "getStringExtra" to "String",
        "getBooleanExtra" to "Boolean",
        "getByteExtra" to "Byte",
        "getCharExtra" to "Char",
        "getShortExtra" to "Short",
        "getIntExtra" to "Int",
        "getLongExtra" to "Long",
        "getFloatExtra" to "Float",
        "getDoubleExtra" to "Double",
        "getStringArrayExtra" to "StringArray",
        "getBooleanArrayExtra" to "BooleanArray",
        "getByteArrayExtra" to "ByteArray",
        "getCharArrayExtra" to "CharArray",
        "getShortArrayExtra" to "ShortArray",
        "getIntArrayExtra" to "IntArray",
        "getLongArrayExtra" to "LongArray",
        "getDoubleArrayExtra" to "DoubleArray",
        "getIntegerArrayListExtra" to "IntArrayList",
        "getStringArrayListExtra" to "StringArrayList",
    )

    private val gettersToParameterTypes = hashMapOf(
        "getQueryParameter" to "String",
        "getQueryParameters" to "StringArray"
    )

    // Intent의 get*Extra 함수 매칭을
    // Uri의 getQueryParameter / getQueryParameters 함수 매칭으로 수정
    private fun visitInvokeNode(node: BaseInvokeNode) {
        // if (node.callMth.declClass.fullName != "android.content.Intent") {
        //     return;
        // }

        // android.net.Uri가 아닌 경우 return
        if (node.callMth.declClass.fullName != "android.net.Uri") {
            return;
        }

        // 함수 이름 확인: getQueryParameter / getQueryParameters
        val extraType = gettersToParameterTypes[node.callMth.name] ?: return
        var key: String? = null;

        for (argument in node.arguments) {
            if (argument is InsnWrapArg) {
                val wrapInsn = argument.wrapInsn
                if (wrapInsn is ConstStringNode) {
                    key = wrapInsn.string
                    break
                }
            }
            else if (argument is RegisterArg) {
                val assignInsn = argument.sVar?.assignInsn
                if (assignInsn is ConstStringNode) {
                    key = assignInsn.string
                    break
                }
            }

            // if (argument !is InsnWrapArg) {
            //     continue;
            // }
            // val wrappedInstruction = argument.wrapInsn
            // if (wrappedInstruction !is ConstStringNode) {
            //     continue;
            // }
            // key = wrappedInstruction.string
        }

        if (key == null) {
            return
        }

        extras[key] = extraType
    }
}
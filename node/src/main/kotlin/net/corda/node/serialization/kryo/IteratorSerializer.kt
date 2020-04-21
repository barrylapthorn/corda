package net.corda.node.serialization.kryo

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.lang.reflect.Field

class IteratorSerializer(private val serializer : Serializer<Iterator<*>>) : Serializer<Iterator<*>>(false, false) {

    private var initNeeded = true
    private var iterableReferenceField: Field? = null
    private var modCountField: Field? = null
    private var expectedModCountField: Field? = null

    override fun write(kryo: Kryo, output: Output, obj: Iterator<*>) {
        serializer.write(kryo, output, obj)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<Iterator<*>>): Iterator<*> {
        val iterator = serializer.read(kryo, input, type)
        return fixIterator(iterator)
    }

    private fun fixIterator(iterator: Iterator<*>) : Iterator<*> {

        if (initNeeded) {
            initialise(iterator)
        }

        val iterableInstance = iterableReferenceField?.get(iterator) ?: return iterator

        val modCountValue = modCountField?.getInt(iterableInstance) ?: return iterator

        // Set expectedModCount of iterator
        expectedModCountField?.setInt(iterator, modCountValue)

        return iterator
    }

    private fun initialise(iterator: Iterator<*>) {

        // Find the outer list
        iterableReferenceField = findField(iterator.javaClass, "this\$0")?.apply { isAccessible = true }

        // Find expectedModCount
        expectedModCountField = findField(iterator.javaClass, "expectedModCount")?.apply { isAccessible = true }

        // Get the modCount of the outer list
        val iterableReferenceFieldType = iterableReferenceField?.type
        if (iterableReferenceFieldType != null) {
            modCountField = findField(iterableReferenceFieldType, "modCount")?.apply { isAccessible = true }
        }

        initNeeded = false
    }

    /**
     * Find field in clazz or any superclass
     */
    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        return clazz.declaredFields.firstOrNull { x -> x.name == fieldName } ?: when {
            clazz.superclass != null -> {
                // Look in superclasses
                findField(clazz.superclass, fieldName)
            }
            else -> null // Not found
        }
    }
}



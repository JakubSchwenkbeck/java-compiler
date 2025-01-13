package de.students.semantic

import de.students.Parser.*

import scala.annotation.tailrec

object UnionTypeFinder {

  def getUnion(typeA: Type, typeB: Type, context: SemanticContext): Type = {
    // if types are equal or B is of NoneType, return A
    if (typeA == typeB || typeB == NoneType) {
      return typeA
    }

    typeA match {
      case NoneType => typeB
      case UserType(typeAName) =>
        typeB match {
          case UserType(typeBName) =>
            val typeAParent = context.getClassRelation(typeAName)
            val typeBParent = context.getClassRelation(typeBName)

            // the combination of classes is only possible, if one is a subtype of the other
            // we will check this recursively

            var foundUnion: Option[Type] = None

            // - check if typeA and typeB have a common parent
            if (typeAParent.nonEmpty && typeBParent.nonEmpty) {
              try
                foundUnion = Some(this.getUnion(UserType(typeAParent.get), UserType(typeBParent.get), context))
              catch
                case _ => // do nothing
            }

            // - check if typeA < typeB
            if (typeAParent.nonEmpty && foundUnion.isEmpty) {
              try // recursively check, if a parent of A is of type B
                foundUnion = Some(this.getUnion(UserType(typeAParent.get), typeB, context))
              catch
                case _ => // do nothing
            }

            // - check if typeB < typeA
            if (typeBParent.nonEmpty && foundUnion.isEmpty) {
              try // recursively check, if a parent of A is of type B
                this.getUnion(typeA, UserType(typeBParent.get), context)
              catch
                case _ => // do nothing
            }

            // return any union we found, or throw an error otherwise
            if (foundUnion.nonEmpty) {
              foundUnion.get
            }
            else {
              // this branch should not even be possible, as both types must be java/lang/Object
              throw SemanticException(s"Types $typeA and $typeB cannot be combined")
            }
          case _ => throw SemanticException(s"Types $typeA and $typeB cannot be combined")
        }
      case _ => throw SemanticException(s"Types $typeA and $typeB cannot be combined")
    }
  }

  /**
   * Check if type A is in any relation a subtype of type B
   *
   * @param typeA   Check if this is the subtype
   * @param typeB   Check if this is the supertype
   * @param context The context with all class information
   * @return
   */
  @tailrec
  def isASubtypeOfB(typeA: Type, typeB: Type, context: SemanticContext): Boolean = {
    if (typeA.equals(typeB)) {
      return true
    }

    typeA match {
      case UserType(className) =>
        val typeAParent = context.getClassRelation(className)
        if (typeAParent.isEmpty) {
          false // We arrived at java/lang/Object and B is still not equal
        }
        else {
          this.isASubtypeOfB(UserType(typeAParent.get), typeB, context) // check if A's parent is a subtype of B
        }
      case _ => typeA == typeB // the trivial case: two primitive types are either equal or not

    }
  }

}
package de.students.semantic;

import de.students.Parser.*

import java.lang.Class
import java.lang.reflect.Modifier
import scala.collection.mutable
import scala.util.matching.Regex

/**
 * A bridge between the local type system and the predefined classes from the JDK. This allows to simply retrieve
 * types of classes, methods and fields by a fully qualified name, regardless of it being a local or a predefined type.
 *
 * @param baseAST The untyped root project
 */
class ClassTypeBridge(baseAST: Project) {

  /**
   * Extract the package name and the simple class name from a fully qualified class name
   *
   * @param fqClassName The fully qualified class name that shall be split
   * @return
   */
  private def splitFullyQualifiedClassName(fqClassName: String): (String, String) = {
    val fqParts: Option[Regex.Match] = """(.+\.)*([^.]+)""".r.findFirstMatchIn(fqClassName)

    fqParts match {
      case None => throw new SemanticException(s"Encountered malformed fully qualified class name: $fqClassName")
      case Some(regMatch) =>
        val rawPackageName = regMatch.group(1)
        if (rawPackageName == null) {
          throw new SemanticException(s"Encountered malformed fully qualified class name: $fqClassName")
        }
        val packageName = if rawPackageName.endsWith(".") then rawPackageName.dropRight(1) else rawPackageName
        val simpleClassName = regMatch.group(2)
        (packageName, simpleClassName)
    }
  }

  /**
   * Convenience access to a class by its classname, independent of it being a local class or from the JDK
   *
   * @param fullyQualifiedClassName The fully qualified name of the class to search for
   * @return
   */
  def getClass(fullyQualifiedClassName: String): ClassDecl = {
    val localClass = this.getLocalClass(fullyQualifiedClassName)
    val reflectionClass = this.getReflectionClass(fullyQualifiedClassName)

    if (localClass.isEmpty && reflectionClass.isEmpty) {
      throw new SemanticException(s"Referenced class $fullyQualifiedClassName is not defined")
    } else if (localClass.nonEmpty && reflectionClass.nonEmpty) {
      throw new SemanticException(s"Class $fullyQualifiedClassName is already defined and cannot be redeclared")
    } else {
      localClass.getOrElse(reflectionClass.get)
    }
  }

  /**
   * Search for a class defined in out own AST and return it with fully qualified names
   *
   * @param fullyQualifiedClassName The fully qualified name of the class to search for
   * @return
   */
  private def getLocalClass(fullyQualifiedClassName: String): Option[ClassDecl] = {
    if (fullyQualifiedClassName.startsWith("[")) {
      return None // we are searching for an array. These are always handled by the reflection classes
    }

    val (packageName, simpleClassName) = this.splitFullyQualifiedClassName(fullyQualifiedClassName)

    val searchResults: List[(ClassDecl, JavaFile)] = baseAST.files
      .filter(pckg => pckg.packageName == packageName)
      .flatMap(pckg => pckg.classes.filter(cls => cls.name == simpleClassName).map(cls => (cls, pckg)))

    val searchResultOption = searchResults.size match {
      case 0 => None
      case 1 => Some(searchResults.head)
      case _ => throw new SemanticException(s"Encountered multiple definitions for class $fullyQualifiedClassName")
    }

    searchResultOption match {
      case Some(searchResult) => {
        // turn every type in classDecl into its fully qualified form
        val (classDecl, pckgDecl) = searchResult

        val classContext = createContext(pckgDecl, classDecl, baseAST)

        Some(
          ClassDecl(
            classDecl.name,
            classContext.simpleTypeToQualified(UserType(classDecl.parent)).asInstanceOf[UserType].name,
            classDecl.isAbstract,
            classDecl.methods.map(method => {
              MethodDecl(
                method.accessModifier,
                method.name,
                method.isAbstract,
                method.static,
                method.isFinal,
                classContext.simpleTypeToQualified(method.returnType),
                method.params.map(param => {
                  VarDecl(
                    param.name,
                    classContext.simpleTypeToQualified(param.varType),
                    param.initializer
                  )
                }),
                method.body
              )
            }),
            classDecl.fields,
            classDecl.constructors
          )
        )
      }
      case None => None
    }
  }

  /**
   * Search for a class defined in the JDK and return it in our own format with fully qualified names
   *
   * @param fullyQualifiedClassName The fully qualified name of the class to search for
   * @return
   */
  private def getReflectionClass(fullyQualifiedClassName: String): Option[ClassDecl] = {
    try {
      val reflectionClass = Class.forName(fullyQualifiedClassName)
      // extract data to our format and fully qualify
      val packageName = reflectionClass.getPackageName

      val parent = reflectionClass.getSuperclass
      val parentName =
        if parent != null then parent.getName
        else {
          if (reflectionClass.isInterface) {
            // TODO: replace this workaround for interfaces with an appropriate handling
            "java.lang.Object"
          } else if (fullyQualifiedClassName == "java.lang.Object") {
            "java.lang.Object" // just let Object be its own parent
          } else { // does this still happen? just to make sure...
            throw new SemanticException(s"Class $fullyQualifiedClassName does not have a parent class")
          }
        }

      Some(
        ClassDecl(
          fullyQualifiedClassName, // className
          parentName, // parentName
          Modifier.isAbstract(reflectionClass.getModifiers), // isAbstract
          reflectionClass.getMethods
            .map(reflectionMethod => {
              val accessModifier: String =
                if Modifier.isPublic(reflectionMethod.getModifiers) then "public"
                else if Modifier.isProtected(reflectionMethod.getModifiers) then "protected"
                else "private"

              val isAbstract = Modifier.isAbstract(reflectionMethod.getModifiers)

              MethodDecl(
                Some(accessModifier),
                reflectionMethod.getName,
                isAbstract,
                Modifier.isStatic(reflectionMethod.getModifiers),
                Modifier.isFinal(reflectionMethod.getModifiers),
                this.reflectionTypeToCustomType(reflectionMethod.getReturnType),
                reflectionMethod.getParameterTypes
                  .map(reflectionParameter => {
                    val parameterType = this.reflectionTypeToCustomType(reflectionParameter)
                    VarDecl(reflectionParameter.getName, parameterType, None)
                  })
                  .toList, // methods
                if isAbstract then None else Some(BlockStatement(List())) // body
              )
            })
            .toList, // methods
          reflectionClass.getFields
            .map(reflectionField => {
              val accessModifier: String =
                if Modifier.isPublic(reflectionField.getModifiers) then "public"
                else if Modifier.isProtected(reflectionField.getModifiers) then "protected"
                else "private"

              FieldDecl(
                Some(accessModifier),
                Modifier.isStatic(reflectionField.getModifiers),
                Modifier.isFinal(reflectionField.getModifiers),
                reflectionField.getName,
                this.reflectionTypeToCustomType(reflectionField.getType),
                None
              )
            })
            .toList, // fields
          reflectionClass.getConstructors
            .map(reflectionConstructor => {
              val accessModifier: String =
                if Modifier.isPublic(reflectionConstructor.getModifiers) then "public"
                else if Modifier.isProtected(reflectionConstructor.getModifiers) then "protected"
                else "private"

              ConstructorDecl(
                Some(accessModifier),
                reflectionConstructor.getName,
                reflectionConstructor.getParameters
                  .map(reflectionParameter => {
                    val parameterType = this.reflectionTypeToCustomType(reflectionParameter.getType)
                    VarDecl(reflectionParameter.getName, parameterType, None)
                  })
                  .toList,
                BlockStatement(List()) // body
              )
            })
            .toList // constructors
        )
      )
    } catch {
      case e: ClassNotFoundException => None
    }
  }

  /**
   * Helper function to convert a reflection type to our custom type case classes
   *
   * @param refType A type retrieved from the reflection data
   * @return
   */
  private def reflectionTypeToCustomType(refType: Class[?]): Type = {
    if refType.isPrimitive then
      refType.getName match {
        case "void"    => VoidType
        case "boolean" => BoolType
        case "byte"    => ByteType
        case "char"    => CharType
        case "short"   => ShortType
        case "int"     => IntType
        case "long"    => LongType
        case "float"   => FloatType
        case "double"  => DoubleType
        case _         => throw new SemanticException(s"Primitive type $refType is not yet implemented")
      }
    else if refType.isArray then ArrayType(this.reflectionTypeToCustomType(refType.getComponentType))
    else UserType(refType.getName)
  }

}

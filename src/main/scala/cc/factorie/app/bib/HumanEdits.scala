package cc.factorie.app.bib
import cc.factorie.util.Attr
import cc.factorie._
import cc.factorie.app.nlp.coref._
import java.io.{PrintWriter, File, BufferedWriter}
import collection.mutable.{HashSet, ArrayBuffer}

/* Keep track of one of these per user */
class UserReliabilityVariable extends RealVariable {
  var totalImpactfulEdits = 0.0
  var totalEdits = 0.0
  def updateValue(d:DiffList) = this.set(totalImpactfulEdits/totalEdits)(d)
}

object HumanEditMention{
  val ET_SHOULD_LINK = "should-link"
  val ET_SHOULD_NOT_LINK = "should-not-link"
}
trait HumanEditMention extends Attr{
  def entity:HierEntity
  def editSet = attr[EditSetVariable]
  attr += new EditSetVariable(entity)
  //var linkSet
  var linkedMention:Option[HierEntity] = None
  var editType:String = "none"
  attr += new UserReliabilityVariable
  var generatedFrom:Option[Entity] = None
}

class EditSetVariable(val entity: Entity) extends SetVariable[HierEntity with HumanEditMention] with EntityAttr

class HumanEditTemplate(val shouldLinkReward:Double=8.0,val shouldNotLinkPenalty:Double=8.0) extends TupleTemplateWithStatistics3[EntityExists, IsEntity, EditSetVariable] {
var debugFlag=false
  def unroll1(eExists: EntityExists) = Factor(eExists,
    eExists.entity.attr[IsEntity], eExists.entity.attr[EditSetVariable])

  def unroll2(isEntity: IsEntity) = Factor(isEntity.entity.attr[EntityExists],
    isEntity, isEntity.entity.attr[EditSetVariable])

  def unroll3(editSetVar: EditSetVariable) = Factor(
    editSetVar.entity.attr[EntityExists], editSetVar.entity.attr[IsEntity],
    editSetVar)

  def score(eExists: EntityExists#Value, isEntity: IsEntity#Value, editSetVar:
  EditSetVariable#Value): Double = {
    def scoreEdit(entity: HierEntity with HumanEditMention) = {
      var result = 0.0
      //we are dividing by 2 because we are looping over both linked mentions (they are in the same edit set).
      if(entity.editType eq HumanEditMention.ET_SHOULD_LINK){
        if(debugFlag)println("  EditTemplate: should-link mention")
        if(entity.entityRoot eq entity.linkedMention.get.entityRoot){
          if(debugFlag)println("    edit template should-link rewarding mention: "+(shouldLinkReward/2.0))
          result += shouldLinkReward/2.0
        } //TODO: else penalty but don't divide by 2?
      }else if(entity.editType eq HumanEditMention.ET_SHOULD_NOT_LINK){
        if(entity.entityRoot eq entity.linkedMention.get.entityRoot)result -= shouldNotLinkPenalty/2.0
      }
      result
    }
    var result = 0.0
    if(eExists.booleanValue && isEntity.booleanValue){
      if(editSetVar.size>0){
        if(debugFlag)println("EditTemplate (debugging). Size="+editSetVar.size)
        for (edit <- editSetVar) {
          result += scoreEdit(edit)
        }
        if(debugFlag)println("  total points: "+result)
      }
    }
    result
  }
}

object HumanEditExperiments{
  trait ExperimentalEdit[T<:HierEntity with HumanEditMention]{
    def score:Double
    def isCorrect:Boolean = score>0
    def mentions:Seq[T]
  }
  class ExpMergeEdit[T<:HierEntity with HumanEditMention](val mention1:T,val mention2:T,val score:Double) extends ExperimentalEdit[T]{
    val mentions:Seq[T]=Seq(mention1,mention2)
    mention1.linkedMention=Some(mention2)
    mention1.editType=HumanEditMention.ET_SHOULD_LINK
    mention2.linkedMention=Some(mention1)
    mention2.editType=HumanEditMention.ET_SHOULD_LINK
    def print:Unit ={
      if(mention1.isInstanceOf[AuthorEntity] && mention2.isInstanceOf[AuthorEntity]){
        println("\n=======PRINTING EDIT=======")
        println("SCORE: "+score)
        println("--EditMention1--")
        EntityUtils.prettyPrintAuthor(mention1.asInstanceOf[AuthorEntity])
        println("--EditMention2--")
        EntityUtils.prettyPrintAuthor(mention2.asInstanceOf[AuthorEntity])
        println("SCORE: "+score)
        println("Mention 1 generated from:")
        EntityUtils.prettyPrintAuthor(mention1.generatedFrom.get.asInstanceOf[AuthorEntity])
        println("Mention 2 generated from:")
        EntityUtils.prettyPrintAuthor(mention2.generatedFrom.get.asInstanceOf[AuthorEntity])
        println("SCORE: "+score)
      }
    }
  }
  class ExpSplitEdit[T<:HierEntity with HumanEditMention](val mention1:T,val mention2:T,val score:Double) extends ExperimentalEdit[T]{
    val mentions:Seq[T]=Seq(mention1,mention2)
    mention1.linkedMention=Some(mention2)
    mention1.editType=HumanEditMention.ET_SHOULD_NOT_LINK
    mention2.linkedMention=Some(mention1)
    mention2.editType=HumanEditMention.ET_SHOULD_NOT_LINK
  }
  def edits2evidenceBatches(edits:Seq[ExpMergeEdit[AuthorEntity]],numBatches:Int):Seq[Seq[AuthorEntity]] ={
    val evidenceBatches = new ArrayBuffer[Seq[AuthorEntity]]
    if(numBatches == -1){
      for(edit <- edits)evidenceBatches.asInstanceOf[ArrayBuffer[Seq[AuthorEntity]]] += edit.mentions
    }else if(numBatches == 1){
      val allEdits = edits.flatMap(_.mentions)
      evidenceBatches.asInstanceOf[ArrayBuffer[Seq[AuthorEntity]]] += allEdits
    }else throw new Exception("Num, evidence batches not implemented for arbitrary values (only -1,1)")
    evidenceBatches
  }
  def getAuthorEdits(initialDB:Seq[AuthorEntity],minimumEntitySize:Int):Seq[ExpMergeEdit[AuthorEntity]] ={
    val edits = allMergeEdits[AuthorEntity](
      initialDB,
      _ => new AuthorEntity,
      (entities:Seq[HierEntity]) => {Evaluator.pairF1(entities).head},
      (original:AuthorEntity) => {
        val cp = new AuthorEntity(original.fullName.firstName, original.fullName.middleName, original.fullName.lastName, true)
        for(bv <- original.attr.all[BagOfWordsVariable]){
          if(!bv.isInstanceOf[BagOfTruths])
            cp.attr(bv.getClass).add(bv.value)(null)
        }
        cp.generatedFrom = Some(original)
        cp.editSet += cp
        cp
      },
      minimumEntitySize
    )
    edits
  }
  def mergeBaseline1(initialDB:Seq[AuthorEntity],evidenceBatches:Seq[Seq[AuthorEntity]],file:File):Unit ={
    val pwbl1 = new PrintWriter(file)
    val d = new DiffList
    var batchName = 0
    val toScore = new ArrayBuffer[AuthorEntity]
    toScore ++= initialDB
    var entityCount = toScore.filter(_.isEntity.booleanValue).size
    val mentionCount = toScore.size
    pwbl1.println("time samples accepted f1 p r batch-count mentions entities batch-name score maxscore")
    pwbl1.println("-1 -1 -1 "+Evaluator.pairF1(toScore).mkString(" ")+" "+mentionCount+" -1 "+batchName+" -1 -1")
    for(batch <- evidenceBatches){
      batchName += 1
      val visited = new HashSet[AuthorEntity]
      for(edit <- batch){
        if(!visited.contains(edit)){
          visited += edit
          val parent = new AuthorEntity
          EntityUtils.linkChildToParent(edit.generatedFrom.get,parent)(d)
          EntityUtils.linkChildToParent(edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get,parent)(d)
          toScore += parent
          entityCount -= 1
        }
      }
      val scores = Evaluator.pairF1(toScore)
      pwbl1.println("-1 -1 -1 "+scores.mkString(" ")+" "+mentionCount+" "+entityCount+" "+batchName+" -1 -1")
      pwbl1.flush()
    }
    d.undo;d.clear;pwbl1.close
  }
  def mergeBaseline2(initialDB:Seq[AuthorEntity],evidenceBatches:Seq[Seq[AuthorEntity]],file:File):Unit ={
    val d2 = new DiffList
    val pwbl2 = new PrintWriter(file)
    val toScore = new ArrayBuffer[AuthorEntity]
    val mentionCount = toScore.size
    var batchName = 0
    toScore ++= initialDB
    var entityCount = toScore.filter(_.isEntity.booleanValue).size
    pwbl2.println("time samples accepted f1 p r batch-count mentions entities batch-name score maxscore")
    pwbl2.println("-1 -1 -1 "+Evaluator.pairF1(toScore).mkString(" ")+" "+mentionCount+" -1 "+batchName+" -1 -1")
    for(batch <- evidenceBatches){
      batchName += 1
      val visited = new HashSet[AuthorEntity]
      for(edit <- batch){
        if(!visited.contains(edit)){
          visited += edit
          val parent = new AuthorEntity
          val epar1 = edit.generatedFrom.get.parentEntity
          val epar2 = edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get.parentEntity
          if(epar1==null && epar2==null){
            EntityUtils.linkChildToParent(edit.generatedFrom.get,parent)(d2)
            EntityUtils.linkChildToParent(edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get,parent)(d2)
            toScore += parent
          } else if(epar1 != null && epar2 == null){
            EntityUtils.linkChildToParent(edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get,epar1)(d2)
          } else if(epar2 != null && epar1 == null){
            EntityUtils.linkChildToParent(edit.generatedFrom.get,epar2)(d2)
          } else if(!edit.generatedFrom.get.entityRoot.eq(edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get.entityRoot)){
            EntityUtils.linkChildToParent(edit.generatedFrom.get.entityRoot.asInstanceOf[AuthorEntity],parent)(d2)
            EntityUtils.linkChildToParent(edit.linkedMention.get.asInstanceOf[AuthorEntity].generatedFrom.get.entityRoot.asInstanceOf[AuthorEntity],parent)(d2)
          }
          entityCount -= 1
        }
      }
      //time samples accepted f1 p r batch-count mentions entities batch-name score maxscore
      val scores = Evaluator.pairF1(toScore)
      pwbl2.println("-1 -1 -1 "+scores.mkString(" ")+" "+mentionCount+" "+entityCount+" "+batchName+" -1 -1")
      pwbl2.flush()
    }
    d2.undo;d2.clear;pwbl2.close
  }
  def allMergeEdits[T<:HierEntity with HumanEditMention ](allEntities:Seq[T],newEntity:Unit=>T,scoreFunction:Seq[HierEntity]=>Double,createEditMentionFrom:T=>T,minESize:Int):Seq[ExpMergeEdit[T]] ={
    val result = new ArrayBuffer[ExpMergeEdit[T]]
    val entities = allEntities.filter((e:T) =>{e.isEntity.booleanValue && e.numLeaves>=minESize})
    println("About to create edits from "+ entities.size + " entities.")
    var i = 0;var j = 0
    while(i<entities.size){
      j = i+1
      val ei = entities(i)
      val eiMentions = ei.descendantsOfClass[HierEntity]
      while(j<entities.size){
        val ej = entities(j)
        val ejMentions = ej.descendantsOfClass[HierEntity]
        val originalScore = scoreFunction(eiMentions ++ ejMentions)
        val tmpRoot = newEntity()
        val d = new DiffList
        //see what happens if we were to merge these two entities together
        EntityUtils.linkChildToParent(ei,tmpRoot)(d)
        EntityUtils.linkChildToParent(ej,tmpRoot)(d)
        val editScore = scoreFunction(eiMentions ++ ejMentions ++ Seq(tmpRoot.asInstanceOf[HierEntity]))
        //undo the merge
        d.undo
        assert(ei.parentEntity == null)
        assert(ej.parentEntity == null)
        //package the finding
        result += new ExpMergeEdit(createEditMentionFrom(ei),createEditMentionFrom(ej),editScore-originalScore)
        j+=1
      }
      i+=1
      print(".")
      if(i % 25 == 0)println
    }
    val numGood = result.filter(_.isCorrect).size
    val numBad = result.size - numGood
    println("Generated "+ result.size + " edits ("+ numGood + " correct edits. " + numBad + " incorrectEdits).")
    result
  }
}
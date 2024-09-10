package mda.generator.readers.xmi;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import mda.generator.beans.UmlAssociation;
import mda.generator.beans.UmlAttribute;
import mda.generator.beans.UmlClass;
import mda.generator.beans.UmlDomain;
import mda.generator.beans.UmlPackage;
import mda.generator.exceptions.MdaGeneratorException;
import mda.generator.readers.ModelFileReaderInterface;

/**
 * Classe de lecture d'un fichier XMI 2.1 issue d'Enterprise Architect
 * @author Fabien Crapart
 *
 */
public class XmiReader implements ModelFileReaderInterface {
	private static Logger LOG = LogManager.getLogger();

	private Map<String, UmlClass> classesMap = new HashMap<>(); // by id
	private Map<String, UmlDomain> domainsMap = new HashMap<>();  // by name
	private Map<String, UmlPackage> packagesMap = new HashMap<>(); // by name
	
	/**
	 * @return the classesMap
	 */
	public Map<String, UmlClass> getClassesMap() {
		return classesMap;
	}

	/**
	 * @return the domainsMap
	 */
	public Map<String, UmlDomain> getDomainsMap() {
		return domainsMap;
	}

	/**
	 * @return the packagesMap
	 */
	public Map<String, UmlPackage> getPackagesMap() {
		return packagesMap;
	}

	/**
	 * Extraction de tous les éléments du fichiers (DOMAINES, PACKAGE, CLASSES, ASSOCIATIONS).
	 */
	public void extractObjects(String pathToXmi, String pathToMetadataXmi) {
		extractMetadata(pathToMetadataXmi);	
		extractModel(pathToXmi);
	}
	
	protected void extractModel(String pathToXmi) {
		try {    			
			DocumentBuilder dBuilder = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder();
			Document doc = dBuilder.parse(new File(pathToXmi));

			// Analyse des packages
			parcourirPackages(doc);

			// On parcours classes et attributs
			parcourirClasses(doc);

			//Associations tagName = connector , 
			parcourirAssociations(doc);

		} catch (Exception e) {
			LOG.error("Erreur lors du parsing du fichier XMI", e);
		}
	}
	
	protected void extractMetadata(String pathToMetadataXmi) {
		try {  
			DocumentBuilder dBuilder = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder();
			Document doc = dBuilder.parse(new File(pathToMetadataXmi));
			
			NodeList elts = doc.getDocumentElement().getElementsByTagName("DataRow");
			for (int idx = 0; idx < elts.getLength(); idx++) {    	
				Node currNode = elts.item(idx);
				if (currNode.getNodeType() == Node.ELEMENT_NODE) {
					List<Node> columns = XmiUtil.getChildsWithTagName(currNode, "Column");
					Map<String, String> columnNameValue = new HashMap<>();
					for(Node column: columns) {
						String name = XmiUtil.getAttribute(column, "name");
						String value = XmiUtil.getAttribute(column, "value");
						columnNameValue.put(name, value);
					}
					
					if("Java".equals(columnNameValue.get("ProductName"))){
						// Dans EA, menu Code, Configure, Product Name Java
						// puis menu Configure, Transfer, Export Reference Data : Model Data Types - Code and DDL
						// -> /model/example_metadata.xml pour la génération
						UmlDomain umlDomain = new UmlDomain();
						umlDomain.setName(columnNameValue.get("DataType")); 
						umlDomain.setTypeName(columnNameValue.get("GenericType"));
						umlDomain.setMaxLength(columnNameValue.get("MaxLen"));
						umlDomain.setPrecision(columnNameValue.get("DefaultPrec"));
						umlDomain.setScale(columnNameValue.get("DefaultScale"));
						domainsMap.put(umlDomain.getName(), umlDomain);
					}
				}
			}
		}	catch (Exception e) {
			LOG.error("Erreur lors du parsing du fichier XMI", e);
		}
	}

	/**
	 * Permet d'extraire les packages et les domaines
	 * @param doc
	 */
	private void parcourirPackages(Document doc) {
		NodeList elts = doc.getDocumentElement().getElementsByTagName("packagedElement");
		for (int idx = 0; idx < elts.getLength(); idx++) {    	
			Node currNode = elts.item(idx);
			if (currNode.getNodeType() == Node.ELEMENT_NODE) {
				XmiElementType type = XmiElementType.getByName(XmiUtil.getElementType(currNode));
				try {
					switch(type) {
					case PACKAGE :
						// On ne traite que les packages java
						String name = XmiUtil.getElementName(currNode);
						if(name.contains(".")) {
							extrairePackage(currNode);
						}
						break;
					default:
						break;
					}
				} catch(Exception e) {
					LOG.error("Type non reconnnu = '" +  XmiUtil.getElementType(currNode)+"'", e);
				}
			}
		}
	}

	/**
	 * Extraction des packages du schéma
	 * @param packageNode
	 */
	protected void extrairePackage(Node packageNode) {
		UmlPackage xmiPackage = new UmlPackage();
		xmiPackage.setId(XmiUtil.getElementId(packageNode));
		xmiPackage.setName(XmiUtil.getElementName(packageNode));

		// On cherche les commentaires
		List<Node>  commentaires = XmiUtil.getChildsWithTagNameAndType(packageNode, "ownedComment", XmiElementType.COMMENT);
		if(!commentaires.isEmpty()) {
			StringBuilder sbCommentaires = new StringBuilder();
			for(Node commentaire : commentaires) {
				sbCommentaires.append(XmiUtil.getElementBody(commentaire)).append("\n");
			}
			xmiPackage.setComment(sbCommentaires.toString());
		}	
		
		// On veut extraite des métadonnées sur les classes et leur attribut
		List<Node> classeNodes = XmiUtil.getChildsWithTagNameAndType(packageNode, "packagedElement", XmiElementType.CLASS);
		for(Node classNode : classeNodes) {
			initClassExtraction(classNode);
		}

		// Ajout dans la map des packages par nom
		packagesMap.put(xmiPackage.getName(), xmiPackage);
	}
	
	/**
	 * Initialise la classe avec quelques données
	 * @param classNode
	 */
	protected void initClassExtraction(Node classNode) {
		UmlClass classeUml =  new UmlClass();
		classeUml.setId(XmiUtil.getElementId(classNode));		
		classesMap.put(classeUml.getId(), classeUml);
		
		List<Node> attributesNodes = XmiUtil.getChildsWithTagNameAndType(classNode, "ownedAttribute", XmiElementType.PROPERTY);
		for(Node attributeNode : attributesNodes) {
			initAttributeExtraction(classeUml, attributeNode);
		}
	}
	
	/**
	 * Initialise un attribut d'une classe avec quelques données
	 * @param attributeNode
	 */
	protected void initAttributeExtraction(UmlClass classeUml, Node attributeNode) {
		// Il s'agit d'un attribut généré via une association, on ne l'intialise
		if(XmiUtil.getAttribute(attributeNode, "association") != null) {
			return;
		}
			
		UmlAttribute attributUml =  new UmlAttribute();
		attributUml.setId(XmiUtil.getElementId(attributeNode));		
		classeUml.addAttribute(attributUml);
		
		// Lecture de métadonnées
		attributUml.setReadonly(XmiUtil.getAttributeIsReadonly(attributeNode));
	}
	
	/**
	 * Extraction des métadonnées de domaine (typage)
	 * @param domainNode
	 */
	protected void extraireDomaine(Node domainNode) {
		UmlDomain xmiDomain = new UmlDomain();
		xmiDomain.setName(XmiUtil.getElementName(domainNode));

		// Ajout dans la map des domaines
		domainsMap.put(xmiDomain.getName(), xmiDomain);
	}

	/**
	 * Parcours des classes et complétion des classes avec métadonnées extraites
	 * @param doc
	 */
	protected void parcourirClasses(Document doc) {
		Node xmiExtension =  doc.getElementsByTagName("xmi:Extension").item(0);
		Node elements = XmiUtil.getFirstChildsNodeWithTagNameAndType(xmiExtension,"elements", null);

		List<Node> elts = XmiUtil.getChildsWithTagNameAndType(elements, "element", XmiElementType.CLASS);
		for (Node currNode : elts) {    	
			if (currNode.getNodeType() == Node.ELEMENT_NODE) {
				XmiElementType type = XmiElementType.getByName(XmiUtil.getElementType(currNode));
				try {
					switch(type) {
					case CLASS :
						extraireClasse(currNode);
						break;						
					default:
						break;
					}
				} catch(Exception e) {
					LOG.error("Error lors du traitement de la node de type '" +  XmiUtil.getElementType(currNode)+"'",e);
				}
			}
		}
	}

	/**
	 * Extraction des données d'une classe (qui existe déjà  car métadonnées extraites en amont)
	 * 
	 * @param classNode
	 */
	protected void extraireClasse(Node classNode) {
		// On récupère la classe dans la map des classes		
		UmlClass xmiClass = classesMap.get(XmiUtil.getElementIdRef(classNode));
		
		xmiClass.setId(XmiUtil.getElementIdRef(classNode));
		xmiClass.setName(XmiUtil.getElementName(classNode));

		// Package extendedProperties tagged="0" package_name="fr.gouv.mindef.gestim.domain.messages"/><attributes>
		Node extendedProperties = XmiUtil.getFirstChildsNodeWithTagName(classNode, "extendedProperties");
		String packageName = XmiUtil.getAttribute(extendedProperties, "package_name");
		UmlPackage xmiPackage = packagesMap.get(packageName);
		xmiPackage.getClasses().add(xmiClass);
		xmiClass.setXmiPackage(xmiPackage);

		// Commentaires <properties documentation="Message à destination des CSNs" i
		Node properties = XmiUtil.getFirstChildsNodeWithTagName(classNode, "properties");
		if(properties != null) {
			xmiClass.setComment(XmiUtil.getAttribute(properties, "documentation"));
		}

		// Attributs
		Node attributes = XmiUtil.getFirstChildsNodeWithTagName(classNode, "attributes");
		if(attributes != null && attributes.hasChildNodes()) {
			for(Node attribut : XmiUtil.getChildsWithTagName(attributes, "attribute")) {
				extractAttribute(attribut, xmiClass);
			}
		}


	}

	/**
	 * Extraction des données d'un attribut (qui existe déjà, car métadonnées extraites en amont)
	 * @param attribut
	 * @param xmiClass
	 */
	protected void extractAttribute(Node attribut, UmlClass xmiClass) {
		// On récupère l'attribut dans la classe		
		UmlAttribute xmiAttribut =  xmiClass.getAttributeById(XmiUtil.getElementIdRef(attribut));
		
		xmiAttribut.setName(XmiUtil.getElementName(attribut));
		// Domain ex:  <properties type="DO_ID" derived="0" collection="false" duplicates="0" changeability="changeable"/>
		Node properties = XmiUtil.getFirstChildsNodeWithTagName(attribut, "properties");
		String domainName = XmiUtil.getAttribute(properties, "type");
		
		UmlDomain domain = domainsMap.get(domainName);
		if(domain == null) {
			throw new MdaGeneratorException("Domain " + domainName + " not found for attribute " + xmiAttribut.getName() + " of class " + xmiClass.getName());
		}
		xmiAttribut.setDomain(domain);

		// Commentaire ex : <style value="Identifiant technique de l'utilisateur"/>
		Node style = XmiUtil.getFirstChildsNodeWithTagName(attribut, "style");
		xmiAttribut.setComment(XmiUtil.getAttribute(style, "value"));

		// Not null ex :  <bounds lower="1" upper="1"/>
		Node bounds = XmiUtil.getFirstChildsNodeWithTagName(attribut, "bounds");
		xmiAttribut.setIsNotNull(!"0".equals(XmiUtil.getAttribute(bounds, "lower")));

		// PK ? ex :  <xrefs value="$XREFPROP=$XID={3C6F55AC-762C-4339-AEA5-6B85C4EEFAB8}$XID;$NAM=CustomProperties$NAM;$TYP=attribute property$TYP;$VIS=Public$VIS;$PAR=0$PAR;$DES=@PROP=@NAME=isID@ENDNAME;@TYPE=Boolean@ENDTYPE;@VALU=1@ENDVALU;@PRMT=@ENDPRMT;@ENDPROP;$DES;$CLT={C11171CB-49AD-4ae1-97B5-32E527D973EB}$CLT;$SUP=<none>$SUP;$ENDXREF;"/>
		Node xrefs = XmiUtil.getFirstChildsNodeWithTagName(attribut, "xrefs");
		String xrefsVals = XmiUtil.getAttribute(xrefs, "value");
		xmiAttribut.setPK(xrefsVals != null && xrefsVals.contains("$DES=@PROP=@NAME=isID@ENDNAME;@TYPE=Boolean@ENDTYPE;@VALU=1@ENDVALU;"));

		// Ajout de l'attribut dans la classe
		xmiClass.getAttributes().add(xmiAttribut);
	}


	protected void parcourirAssociations(Document doc) {
		Node xmiExtension =  doc.getElementsByTagName("xmi:Extension").item(0);
		Node connectors = XmiUtil.getFirstChildsNodeWithTagName(xmiExtension,"connectors");

		List<Node> elts = XmiUtil.getChildsWithTagName(connectors, "connector");
		for (Node currNode : elts) {    	
			Node source = XmiUtil.getFirstChildsNodeWithTagName(currNode, "source");
			Node target = XmiUtil.getFirstChildsNodeWithTagName(currNode, "target");
			// Nom de l'association ex : <labels lb="0..*" mt="ORG_TOR" rb="1"/>
			Node labels = XmiUtil.getFirstChildsNodeWithTagName(currNode, "labels");
			String assocName = XmiUtil.getAttribute(labels, "mt");

			UmlClass classSource = classesMap.get(XmiUtil.getElementIdRef(source));		
			UmlClass classTarget = classesMap.get(XmiUtil.getElementIdRef(target));		
			if(assocName == null) {
				throw new MdaGeneratorException("Association between "+ classSource.getName() +" and " + classTarget.getName() + " has no name");
			}

			UmlAssociation sourceToTarget = extraireAssociation(assocName, classSource, classTarget, target);

			UmlAssociation targetToSource = extraireAssociation(assocName, classTarget, classSource, source);
			if((sourceToTarget.isTargetOwned() && targetToSource.isTargetOwned())  || 
					(!sourceToTarget.isTargetOwned() && !targetToSource.isTargetOwned())) {
				// Si aucun n'est owner (ou les deux), on choisit arbitrairement la sources (utile pour n:m et 1:1)
				sourceToTarget.setTargetOwned(true); 
			}
			
			
			// On partage les infos entre les associations
			sourceToTarget.setOpposite(targetToSource);			
			targetToSource.setOpposite(sourceToTarget);
		}
	}

	protected UmlAssociation extraireAssociation(String assocName, UmlClass owner,UmlClass target, Node targetNode) {
		UmlAssociation umlAssoc = new UmlAssociation();
		umlAssoc.setName(assocName);
		umlAssoc.setSource(owner);
		umlAssoc.setTarget(target);
		owner.getAssociations().add(umlAssoc);

		// Calcul de la multiplicté et nullabilité ex: <type multiplicity="0..1" 
		String cardinalite = XmiUtil.getAttribute(XmiUtil.getFirstChildsNodeWithTagName(targetNode, "type"), "multiplicity");
		switch(cardinalite) {
		case "1":
			umlAssoc.setTargetMultiple(false);
			umlAssoc.setTargetNullable(false);
			break;
		case "0..1":
			umlAssoc.setTargetMultiple(false);
			umlAssoc.setTargetNullable(true);
			break;
		case "0..*":
			umlAssoc.setTargetMultiple(true);
			umlAssoc.setTargetNullable(true);
			break;
		case "1..*":
			umlAssoc.setTargetMultiple(true);
			umlAssoc.setTargetNullable(false);
			break;
		default :
			LOG.error("Cardinalité non gérée : " + cardinalite);
			break;
		}
		
		// Navigabilité, ex: <modifiers isOrdered="false" changeable="none" isNavigable="false"/>
		String navigabilite = XmiUtil.getAttribute(XmiUtil.getFirstChildsNodeWithTagName(targetNode, "modifiers"), "isNavigable");
		if("true".equals(navigabilite)) {
			umlAssoc.setTargetNavigable(true);
		}else {
			umlAssoc.setTargetNavigable(false);
		}
			
		// Facultatif, récupérer le nom de l'objet FK ex: <role name="organismeParent"
		String fkObjectName = XmiUtil.getAttribute(XmiUtil.getFirstChildsNodeWithTagName(targetNode, "role"), "name");
		umlAssoc.setFkObjectName(fkObjectName);
		
		// Facultatif, récupérer le nom de la colonne FK <style value="Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable=Navigable;alias=workplace_service_id;"/>
		String stylesValues = XmiUtil.getAttribute(XmiUtil.getFirstChildsNodeWithTagName(targetNode, "style"), "value");
		for(String styleValue : stylesValues.split(";")) {
			if(styleValue != null ) {
				// Nom de la FK
				if(styleValue.startsWith("alias=")) {
					umlAssoc.setFkName(styleValue.split("=")[1]);
				}
				// Owner de la relation (pour 1:1 et n:m)
				else if(styleValue.startsWith("Owned=")) {					
					// Si owned vaut 1, c'est l'autre qui est owner de l'association
					umlAssoc.setTargetOwned(styleValue.split("=")[1].equals("1")? true: false);
				}
			}
		}
		
		return umlAssoc;		
	}

}
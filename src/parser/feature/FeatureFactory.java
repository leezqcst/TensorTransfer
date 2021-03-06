package parser.feature;

import static parser.feature.FeatureTemplate.Arc.numArcFeatBits;

import java.io.Serializable;

import gnu.trove.set.hash.TIntHashSet;
import gnu.trove.set.hash.TLongHashSet;
import parser.DependencyInstance;
import parser.Options;
import parser.Parameters;
import parser.Options.TensorMode;
import parser.feature.FeatureTemplate.Arc;
import parser.tensor.LowRankParam;
import parser.tensor.ParameterNode;
import utils.Alphabet;
import utils.FeatureVector;
import utils.TypologicalInfo;
import utils.Utils;
import utils.TypologicalInfo.TypoFeatureType;
import utils.WordVector;

public class FeatureFactory implements Serializable {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	Options options;
	
	public int TOKEN_START = 1;
	public int TOKEN_END = 2;
	public int TOKEN_MID = 3;
	public int POS_NOUN = 4;
	public int POS_PRON = 5;
	public int POS_ADJ = 6;
	public int POS_VERB = 7;
	public int POS_ADP = 8;
	public int LABEL_SBJ = 2;
	public int LABEL_DOBJ = 3;
	public int LABEL_IOBJ = 4;
	public int LABEL_SBJPASS = 5;
	
	public int tagNumBits, depNumBits, flagBits;
	public int wordNumBits;

	public int posNum, labelNum;
    public ParameterNode pn;
	public transient TypologicalInfo typo;
	public transient FeatureRepo fr;
	public transient WordVector wv;

    //public Alphabet arcAlphabet;
	//public int numArcFeats;	// number of arc structure features

	//public final int numArcFeats = 10503061; //115911564;	// number of arc structure features
	//public final int numLabeledArcFeats = 10503061; //115911564;
	public final int numArcFeats = 115911564; //;	// number of arc structure features
	public final int numLabeledArcFeats = 115911564; //;
	
	private boolean stoppedGrowth;
	public transient TLongHashSet featureHashSet;
	public transient TIntHashSet featureIDSet;

	public FeatureFactory(Options options) {
		this.options = options;
		//arcAlphabet = new Alphabet();
		//numArcFeats = 0;
		stoppedGrowth = false;
		featureHashSet = new TLongHashSet(100000);
		featureIDSet = new TIntHashSet(100000);
	}

	public void closeAlphabets()
	{
		//arcAlphabet.stopGrowth();
		stoppedGrowth = true;
	}

	public void initFeatureAlphabets(DependencyInstance inst) {
		// add CRF features
		initCRFFeatureVector(inst);
		
		// tensor features
		if (options.tensorMode == TensorMode.Threeway)
			initThreewayFeatureAlphabets(inst);
		else if (options.tensorMode == TensorMode.Multiway)
			initMultiwayFeatureAlphabets(inst);
		else if (options.tensorMode == TensorMode.Hierarchical)
			initHierarchicalFeatureAlphabets(inst);
		else if (options.tensorMode == TensorMode.TMultiway)
			initTMultiwayFeatureAlphabets(inst);
		else
			Utils.ThrowException("not supported structure");
	}
	
	public void initCRFFeatureVector(DependencyInstance inst) {
		int n = inst.length;
		for (int i = 1; i < n; ++i) {
			createArcFeatures(inst, inst.heads[i], i);
			createArcLabelFeatures(inst, inst.heads[i], i, inst.deplbids[i]);
		}
	}
	
	public void initThreewayFeatureAlphabets(DependencyInstance inst) {
		// head and modifier
		int n = inst.length;
		
		for (int i = 1; i < n; ++i) {
			int h = inst.heads[i];
			FeatureVector fv = createThreewayPosFeatures(inst, h, pn.node[0].featureSize, pn.node[0].featureBias);
			pn.node[0].setActiveFeature(fv);
			
			fv = createThreewayPosFeatures(inst, i, pn.node[0].featureSize, pn.node[0].featureBias);
			pn.node[1].setActiveFeature(fv);	// root cannot be the modifier

			fv = fr.getDDFv(getBinnedDistance(h - i));
			pn.node[2].setActiveFeature(fv);

			if (options.learnLabel) {
				fv = fr.getLabelFv(inst.deplbids[i]);
				pn.node[3].setActiveFeature(fv);
			}
		}
	}
	
	public void initMultiwayFeatureAlphabets(DependencyInstance inst) {
		// head and modifier
		int n = inst.length;
		
		for (int i = 0; i < n; ++i) {
			int p = inst.postagids[i];
			int pp = i > 0 ? inst.postagids[i - 1] : TOKEN_START;
			int np = i < n - 1 ? inst.postagids[i + 1] : TOKEN_END;

			FeatureVector fv = fr.getContextFv(pp, np);
			pn.node[2].setActiveFeature(fv);
			
			if (i > 0) {
				int h = inst.heads[i];
				int hp = inst.postagids[h];
				int binDist = getBinnedDistance(h - i);
				
				fv = fr.getPOSFv(hp);
				pn.node[0].setActiveFeature(fv);
				
				fv = fr.getPOSFv(p);
				pn.node[1].setActiveFeature(fv);
				
				fv = fr.getContextFv(pp, np);
				pn.node[3].setActiveFeature(fv);
				
				fv = fr.getDDFv(binDist);
				pn.node[4].setActiveFeature(fv);

				if (options.learnLabel) {
					fv = fr.getLabelFv(inst.deplbids[i]);
					pn.node[5].setActiveFeature(fv);
				}
			}

		}
	}
	
	public void initHierarchicalFeatureAlphabets(DependencyInstance inst) {
		int n = inst.length;
		ParameterNode delexical = pn;
		if (options.lexical) {
			delexical = pn.node[1];
			
			ParameterNode hpn = pn.node[0].node[0];
			ParameterNode mpn = pn.node[0].node[1];
			for (int i = 0; i < n; ++i) {
				FeatureVector fv = createLexicalFeatures(inst, i, hpn.featureSize, hpn.featureBias);
				hpn.setActiveFeature(fv);
				if (i > 0) {
					mpn.setActiveFeature(fv);
				}
			}
		}
		
		for (int i = 0; i < n; ++i) {
			int p = inst.postagids[i];
			int pp = i > 0 ? inst.postagids[i - 1] : TOKEN_START;
			int np = i < n - 1 ? inst.postagids[i + 1] : TOKEN_END;
			
			FeatureVector fv = fr.getContextFv(pp, np, inst.lang);
			delexical.node[0].setActiveFeature(fv);
			if (i > 0) {
				int h = inst.heads[i];
				int hp = inst.postagids[h];
				int binDist = getBinnedDistance(h - i);
				
				delexical.node[1].setActiveFeature(fv);
				if (options.learnLabel) {
					int label = inst.deplbids[i];
					
					ParameterNode arc = delexical.node[2];
					fv = fr.getSVOFv(hp, p, label, binDist, inst.lang);
					arc.setActiveFeature(fv);
					
					fv = fr.getLabelFv(label);
					arc.node[0].setActiveFeature(fv);
					
					ParameterNode typo = arc.node[1];
					fv = fr.getTypoFv(hp, p, binDist, inst.lang);
					typo.setActiveFeature(fv);
					
					fv = fr.getPOSFv(hp);
					typo.node[0].setActiveFeature(fv);
					fv = fr.getPOSFv(p);
					typo.node[1].setActiveFeature(fv);
					fv = fr.getDDTypoFv(binDist, inst.lang);
					typo.node[2].setActiveFeature(fv);
				}
				else {
					ParameterNode typo = delexical.node[2];
					fv = fr.getTypoFv(hp, p, binDist, inst.lang);
					typo.setActiveFeature(fv);
					
					fv = fr.getPOSFv(hp);
					typo.node[0].setActiveFeature(fv);
					fv = fr.getPOSFv(p);
					typo.node[1].setActiveFeature(fv);
					fv = fr.getDDTypoFv(binDist, inst.lang);
					typo.node[2].setActiveFeature(fv);
				}
			}
			
		}
	}
	
	public void initTMultiwayFeatureAlphabets(DependencyInstance inst) {
		// head and modifier
		int n = inst.length;
		
		if (options.lexical) {
			ParameterNode hpn = pn.node[7];
			ParameterNode mpn = pn.node[8];
			for (int i = 0; i < n; ++i) {
				FeatureVector fv = createLexicalFeatures(inst, i, hpn.featureSize, hpn.featureBias);
				hpn.setActiveFeature(fv);
				if (i > 0) {
					mpn.setActiveFeature(fv);
				}
			}
		}

		for (int i = 0; i < n; ++i) {
			int p = inst.postagids[i];
			int pp = i > 0 ? inst.postagids[i - 1] : TOKEN_START;
			int np = i < n - 1 ? inst.postagids[i + 1] : TOKEN_END;

			FeatureVector fv = fr.getContextFv(pp, np, inst.lang);
			pn.node[2].setActiveFeature(fv);
			if (i > 0) {
				int h = inst.heads[i];
				int hp = inst.postagids[h];
				int binDist = getBinnedDistance(h - i);
				
				pn.node[3].setActiveFeature(fv);
				int label = inst.deplbids[i];

				fv = createAllTypoFeatures(hp, p, label, binDist, inst.lang, pn.node[4].featureSize, pn.node[4].featureBias);
				pn.node[4].setActiveFeature(fv);

				fv = fr.getLabelFv(label);
				pn.node[6].setActiveFeature(fv);

				fv = fr.getPOSFv(hp);
				pn.node[0].setActiveFeature(fv);
				fv = fr.getPOSFv(p);
				pn.node[1].setActiveFeature(fv);
				fv = fr.getDDTypoFv(binDist, inst.lang);
				pn.node[5].setActiveFeature(fv);
			}

		}
	}
	
	/***
	 * tensor features
	 */

    public FeatureVector createThreewayPosFeatures(DependencyInstance inst, int i, int dim, int[] bias) 
    {
    	int[] pos = inst.postagids;
        
        FeatureVector fv = new FeatureVector(dim);
    	
        int p0 = pos[i];
    	int pLeft = i > 0 ? pos[i-1] : TOKEN_START;
    	int pRight = i < pos.length-1 ? pos[i+1] : TOKEN_END;
    	
    	Utils.Assert(p0 < posNum);

    	int code = 0;
        
    	// bias
    	code = 0;
    	fv.addEntry(code);

    	// p0, p-1, p1
    	code = bias[0] + p0;
    	fv.addEntry(code);
    	code = bias[1] + pLeft;
    	fv.addEntry(code);
    	code = bias[2] + pRight;
    	fv.addEntry(code);
    	
    	// (p-1,p0), (p0,p1)
    	code = bias[3] + getPPCode(pLeft, p0);
    	fv.addEntry(code);
    	code = bias[4] + getPPCode(p0, pRight);
    	fv.addEntry(code);
    	
    	return fv;
    }
    
    public FeatureVector createDirDistFeatures(int binDist, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	fv.addEntry(code);
    	fv.addEntry(bias[0] + binDist);
    	
    	return fv;
    }
    
    public FeatureVector createDirDistTypoFeatures(int binDist, int lang, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	int d = ParameterNode.d;
    	fv.addEntry(code);
    	
    	int c = typo.getClass(lang);
    	int f = typo.getFamily(lang);
    	code = c;
    	fv.addEntry(bias[0] + code);
    	code = f;
    	fv.addEntry(bias[1] + code);
    	
    	code = binDist >= d ? binDist - d : binDist;
    	fv.addEntry(bias[2] + code);

    	//int c = 0;
    	code = c * 2 * d + binDist;
    	fv.addEntry(bias[3] + code);
    	
    	code = f * 2 * d + binDist;
    	fv.addEntry(bias[4] + code);
    	
    	return fv;
    }
    
    public FeatureVector createLabelFeatures(int label, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	fv.addEntry(code);
    	fv.addEntry(bias[0] + label);
    	
    	return fv;
    }
    
    public FeatureVector createPosFeatures(int p, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	fv.addEntry(code);
    	fv.addEntry(bias[0] + p);
    	
    	return fv;
    }
    
    public FeatureVector createLexicalFeatures(DependencyInstance inst, int i, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	int lang = inst.lang;
    	int wordVecId = inst.wordVecIds[i];
    	
    	int code = 0;
    	fv.addEntry(code);
    	
    	if (wordVecId >= 0) {
    		double[] v = wv.getWordVec(lang, wordVecId);
    		for (int j = 0, L = v.length; j < L; ++j) {
    			fv.addEntry(bias[0] + j, v[j]);
    		}
    	}
    	
    	int transid = inst.transids[i];
    	if (transid >= 0) {
    		fv.addEntry(bias[1] + transid);
    	}
    	
    	return fv;
    }

    public FeatureVector createContextPOSFeatures(int pp, int np, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	int code = 0;
    	fv.addEntry(code);
    	
    	code = pp;
    	fv.addEntry(bias[0] + code);
    	code = np;
    	fv.addEntry(bias[1] + code);
    	
    	return fv;
    }
    
    public FeatureVector createContextPOSFeatures(int pp, int np, int lang, int dim, int[] bias) {
    	FeatureVector fv = new FeatureVector(dim);
    	int code = 0;
    	fv.addEntry(code);
    	
    	int c = typo.getClass(lang);
    	int f = typo.getFamily(lang);
    	
    	//code = c;
    	//fv.addEntry(bias[0] + code);
    	//code = f;
    	//fv.addEntry(bias[1] + code);
    	
    	//int c = 0;
    	code = c * posNum + pp;
    	fv.addEntry(bias[2] + code);
    	code = f * posNum + pp;
    	fv.addEntry(bias[3] + code);
    	
    	code = c * posNum + np;
    	fv.addEntry(bias[4] + code);
    	code = f * posNum + np;
    	fv.addEntry(bias[5] + code);
    	
    	//code = pp;
    	//fv.addEntry(bias[4] + code);
    	//code = np;
    	//fv.addEntry(bias[5] + code);
    	
    	return fv;
    }
    
    public FeatureVector createSVOFeatures(int hp, int mp, int label, int binDist, int lang, int dim, int[] bias) {
    	// no bias feature
    	
    	FeatureVector fv = new FeatureVector(dim);
    	if (hp != POS_VERB || (mp != POS_NOUN && mp != POS_PRON) 
    			|| (label != LABEL_SBJ && label != LABEL_SBJPASS && label != LABEL_DOBJ && label != LABEL_IOBJ))
    		Utils.ThrowException("should not go here");
    	
    	int code = 0;
    	int d = ParameterNode.d;
    	int dir = binDist < d ? 0 : 1;		//0: left; 1: right
    	int offset = 4;
    	//int dir = binDist;
    	//int offset = 2 * d * 2;
    	if (mp == POS_NOUN) {
    		if (label == LABEL_SBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2;
    			fv.addEntry(code);
    		}
    		else if (label == LABEL_SBJPASS) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			fv.addEntry(code);
    		}
    		else if (label == LABEL_DOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2;
    			fv.addEntry(bias[1] + code);
    		}
    		else if (label == LABEL_IOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			fv.addEntry(bias[1] + code);
    		}
    	}
    	else if (mp == POS_PRON) {
    		if (label == LABEL_SBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2;
    			fv.addEntry(bias[0] + code);
    		}
    		else if (label == LABEL_SBJPASS) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			fv.addEntry(bias[0] + code);
    		}
    		else if (label == LABEL_DOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2;
    			fv.addEntry(bias[2] + code);
    		}
    		else if (label == LABEL_IOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			fv.addEntry(bias[2] + code);
    		}
    	}

    	/*
    	int code = 0;
    	int d = ParameterNode.d;
    	if (l == LABEL_SBJ || l == LABEL_SBJPASS) {
    		int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    		int offset = 2 + 2 * d;
    		
    		boolean leftArc = binDist < d ? true : false;
    		code = binDist < d ? 0 : 1;		// 0: left
    		fv.addEntry(v * offset + code);
    		//code = 2 + binDist;
    		//fv.addEntry(v * offset + code);
    		
    		if (v == 0) {
    			// SV
    			code = leftArc ? 0 : 1;	// 1: violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    		else if (v == 1) {
    			// VS
    			code = leftArc ? 1 : 0;	// 1: violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    		else {
    			code = 0;		// not violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    	}
    	else {
    		int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    		int offset = 2 + 2 * d;

    		boolean leftArc = binDist < d ? true : false;
    		code = binDist < d ? 0 : 1;		// 0: left
    		fv.addEntry(bias[1] + v * offset + code);
    		//code = 2 + binDist;
    		//fv.addEntry(bias[1] + v * offset + code);
    		
    		if (v == 0) {
    			// VO
    			code = leftArc ? 1 : 0;	// 1: violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    		else if (v == 1) {
    			// OV
    			code = leftArc ? 0 : 1;	// 1: violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    		else {
    			code = 0;		// not violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    	}
    	*/
    	return fv;
    }
    
    public FeatureVector createTypoFeatures(int hp, int mp, int binDist, int lang, int dim, int[] bias) {
    	// no bias feature
    	
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	int d = ParameterNode.d;
    	int dir = binDist < d ? 0 : 1;		//0: left; 1: right
    	int offset = 2;
    	//int dir = binDist;
    	//int offset = 2 * d;
    	
    	if (hp == POS_ADP && mp == POS_NOUN) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Prep.ordinal()];
    		code = v * offset + dir;
    		fv.addEntry(code);
    	}
    	else if (hp == POS_ADP && mp == POS_PRON) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Prep.ordinal()];
    		code = v * offset + dir;
    		fv.addEntry(bias[0] + code);
    	}
    	else if (hp == POS_NOUN && mp == POS_NOUN) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Gen.ordinal()];
    		code = v * offset + dir;
    		fv.addEntry(bias[1] + code);
    	}
    	else if (hp == POS_NOUN && mp == POS_ADJ) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Adj.ordinal()];
    		code = v * offset + dir;
    		fv.addEntry(bias[2] + code);
    	}
    	
    	/*
    	int code = 0;
    	int d = ParameterNode.d;
    	
    	if (hp == POS_ADP && (mp == POS_NOUN || mp == POS_PRON)) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Prep.ordinal()];
    		int offset = 2 + 2 * d;
    		
    		boolean leftArc = binDist < d ? true : false;
    		code = binDist < d ? 0 : 1;		// 0: left
    		fv.addEntry(v * offset + code);
    		//code = 2 + binDist;
    		//fv.addEntry(v * offset + code);
    		
    		if (v == 0) {
    			// Prep
    			code = leftArc ? 1 : 0;	// 1: violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    		else if (v == 1) {
    			// PostP
    			code = leftArc ? 0 : 1;	// 1: violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    		else {
    			code = 0;		// not violate
    			fv.addEntry(bias[0] + code);
    			//code = 2 + binDist;
    			//fv.addEntry(bias[0] + code);
    		}
    	}
    	else if (hp == POS_NOUN && mp == POS_NOUN) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Gen.ordinal()];
    		int offset = 2 + 2 * d;
    		
    		boolean leftArc = binDist < d ? true : false;
    		code = binDist < d ? 0 : 1;		// 0: left
    		fv.addEntry(bias[1] + v * offset + code);
    		//code = 2 + binDist;
    		//fv.addEntry(bias[1] + v * offset + code);
    		
    		if (v == 0) {
    			// Gen-Noun
    			code = leftArc ? 0 : 1;	// 1: violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    		else if (v == 1) {
    			// Noun-Gen
    			code = leftArc ? 1 : 0;	// 1: violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    		else {
    			code = 0;		// not violate
    			fv.addEntry(bias[2] + code);
    			//code = 2 + binDist;
    			//fv.addEntry(bias[2] + code);
    		}
    	}
    	else if (hp == POS_NOUN && mp == POS_ADJ) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Adj.ordinal()];
    		int offset = 2 + 2 * d;
    		
    		boolean leftArc = binDist < d ? true : false;
    		code = binDist < d ? 0 : 1;		// 0: left
    		fv.addEntry(bias[3] + v * offset + code);
    		//code = 2 + binDist;
    		//fv.addEntry(bias[3] + v * offset + code);
    		
    		if (v == 0) {
    			// Adj-Noun
    			code = leftArc ? 0 : 1;	// 1: violate
    			fv.addEntry(bias[4] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[4] + code);
    		}
    		else if (v == 1) {
    			// Noun-Adj
    			code = leftArc ? 1 : 0;	// 1: violate
    			fv.addEntry(bias[4] + code);
    			//code = 2 + code * 2 * d + binDist;
    			//fv.addEntry(bias[4] + code);
    		}
    		else {
    			code = 0;		// not violate
    			fv.addEntry(bias[4] + code);
    			//code = 2 + binDist;
    			//fv.addEntry(bias[4] + code);
    		}
    	}
    	*/
    	return fv;
    }
    
    public FeatureVector createAllTypoFeatures(int hp, int mp, int label, int binDist, int lang, int dim, int[] bias) {
    	// no bias feature
    	
    	FeatureVector fv = new FeatureVector(dim);
    	
    	int code = 0;
    	fv.addEntry(code);
    	
    	int d = ParameterNode.d;
    	int dir = binDist < d ? 0 : 1;		//0: left; 1: right
    	int offset = 4;
    	//int dir = binDist;
    	//int offset = 2 * d * 2;
    	
    	
    	if (mp == POS_NOUN) {
    		if (label == LABEL_SBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2;
    			//code = v;
    			fv.addEntry(bias[0] + code);
    		}
    		else if (label == LABEL_SBJPASS) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			//code = v;
    			fv.addEntry(bias[0] + code);
    		}
    		else if (label == LABEL_DOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2;
    			//code = v;
    			fv.addEntry(bias[2] + code);
    		}
    		else if (label == LABEL_IOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			//code = v;
    			fv.addEntry(bias[2] + code);
    		}
    	}
    	else if (mp == POS_PRON) {
    		if (label == LABEL_SBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2;
    			//code = v;
    			fv.addEntry(bias[1] + code);
    		}
    		else if (label == LABEL_SBJPASS) {
    			int v = typo.getFeature(lang)[TypoFeatureType.SV.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			//code = v;
    			fv.addEntry(bias[1] + code);
    		}
    		else if (label == LABEL_DOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2;
    			//code = v;
    			fv.addEntry(bias[3] + code);
    		}
    		else if (label == LABEL_IOBJ) {
    			int v = typo.getFeature(lang)[TypoFeatureType.VO.ordinal()];
    			code = v * offset + dir * 2 + 1;
    			//code = v;
    			fv.addEntry(bias[3] + code);
    		}
    	}
    	
    	offset = 2;
    	//int dir = binDist;
    	//int offset = 2 * d;
    	
    	if (hp == POS_ADP && mp == POS_NOUN) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Prep.ordinal()];
    		code = v * offset + dir;
			//code = v;
    		fv.addEntry(bias[4] + code);
    	}
    	else if (hp == POS_ADP && mp == POS_PRON) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Prep.ordinal()];
    		code = v * offset + dir;
			//code = v;
    		fv.addEntry(bias[5] + code);
    	}
    	else if (hp == POS_NOUN && mp == POS_NOUN) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Gen.ordinal()];
    		code = v * offset + dir;
			//code = v;
    		fv.addEntry(bias[6] + code);
    	}
    	else if (hp == POS_NOUN && mp == POS_ADJ) {
    		int v = typo.getFeature(lang)[TypoFeatureType.Adj.ordinal()];
    		code = v * offset + dir;
			//code = v;
    		fv.addEntry(bias[7] + code);
    	}
    	
    	return fv;
    }

    /***
     * traditional features
     */
    
    public FeatureVector createArcFeatures(DependencyInstance inst, int h, int m) 
    {
    	FeatureVector fv = new FeatureVector(numArcFeats);
    	
    	//if (options.useSupervised && inst.lang == options.targetLang) {
    	//	addSupervisedFeatures(fv, inst, h, m, getBinnedDistance(h - m) + 1);
    	//}
    	
    	if (options.direct)
    		addDelexicalFeatures(inst, h, m, fv);
    	else {
    		addDelexicalCFFeatures(inst, h, m, fv);
    		addBareFeatures(inst, h, m, fv);
    		addSelectiveFeatures(inst, h, m, fv);
    	}
    	
    	return fv;
    }
    
    
    public void addDelexicalFeatures(DependencyInstance inst, int h, int m, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int attDist = getBinnedDistance(h - m) + 1;
		int n = inst.length;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;
		int HPp = (h > 0 ? pos[h - 1] : TOKEN_START) + 1;
		int HPn = (h < n - 1 ? pos[h + 1] : TOKEN_END) + 1;
		int MPp = (m > 0 ? pos[m - 1] : TOKEN_START) + 1;
		int MPn = (m < n - 1 ? pos[m + 1] : TOKEN_END) + 1;
		
    	code = createArcCodeP(Arc.ATTDIST, 0);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeP(Arc.HP, HP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodeP(Arc.MP, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePP(Arc.HP_MP, HP, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HPp_HP_MP, HPp, HP, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_HPn_MP, HP, HPn, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_MPp_MP, HP, MPp, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_MP_MPn, HP, MP, MPn);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);

    	int large = Math.max(h, m);
    	int small = Math.min(h, m);
    	
    	for (int i = small + 1; i < large; ++i) {
    		int BP = pos[i] + 1;
        	code = createArcCodePPP(Arc.HP_BP_MP, HP, BP, MP);
        	addArcFeature(code, fv);
    	}

		if (inst.wordVecIds[h] >= 0) {
			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[h]);
			for (int i = 0; i < v.length; ++i) {
				code = createArcCodeW(Arc.HEAD_EMB, i + 1);
				addArcFeature(code, v[i], fv);
				addArcFeature(code | attDist, v[i], fv);
			}
		}
		
		if (inst.wordVecIds[m] >= 0) {
			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[m]);
			for (int i = 0; i < v.length; ++i) {
				code = createArcCodeW(Arc.MOD_EMB, i + 1);
				//addArcFeature(code, v[i], fv);
				addArcFeature(code | attDist, v[i], fv);
			}
		}

		if (inst.transids[h] >= 0) {
			int head = inst.transids[h] + 1;
	    	code = createArcCodeWPP(Arc.HW_HP_MP, head, HP, MP);
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
	    	
	    	code = createArcCodeWP(Arc.HW_MP, head, MP);
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
		}
		
		if (inst.transids[m] >= 0) {
			int mod = inst.transids[m] + 1;
	    	code = createArcCodeWPP(Arc.MW_HP_MP, mod, HP, MP);
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
	    	
	    	code = createArcCodeWP(Arc.MW_HP, mod, HP);
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
			
		}
    }
    
    public void addDelexicalCFFeatures(DependencyInstance inst, int h, int m, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int attDist = getBinnedDistance(h - m) + 1;
		int c = ((typo.getClass(inst.lang) + 1) << numArcFeatBits) << flagBits;
		//int f = ((typo.getFamily(inst.lang) + typo.classNum + 1) << numArcFeatBits) << flagBits;

		int typoVecDim = 1;
		int[] f = new int[typoVecDim];
		double[] v = new double[typoVecDim];
		f[0] = ((typo.getFamily(inst.lang) + typo.classNum + 1) << numArcFeatBits) << flagBits;
		v[0] = 1.0;

//		int typoVecDim = options.typoVecDim;
//		int[] f = new int[options.typoVecDim];
//		double[] v = typo.getTypoVec(inst.lang);
//		for (int i = 0; i < options.typoVecDim; ++i) {
//			f[i] = ((i + typo.classNum + 1) << numArcFeatBits) << flagBits;
//		}

		int n = inst.length;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;
		int HPp = (h > 0 ? pos[h - 1] : TOKEN_START) + 1;
		int HPn = (h < n - 1 ? pos[h + 1] : TOKEN_END) + 1;
		int MPp = (m > 0 ? pos[m - 1] : TOKEN_START) + 1;
		int MPn = (m < n - 1 ? pos[m + 1] : TOKEN_END) + 1;
		
    	code = createArcCodePP(Arc.ATTDIST, 0, 0);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);
    	
    	code = createArcCodePP(Arc.HP, HP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePP(Arc.MP, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPP(Arc.HP_MP, HP, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HPp_HP_MP, HPp, HP, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MP, HP, HPn, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_MPp_MP, HP, MPp, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_MP_MPn, HP, MP, MPn, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP, 0);
    	addArcFeature(code | c, fv);
    	//addArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addArcFeature(code | c | attDist, fv);
    	//addArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	int large = Math.max(h, m);
    	int small = Math.min(h, m);
    	
    	for (int z = small + 1; z < large; ++z) {
    		int BP = pos[z] + 1;
        	code = createArcCodePPPP(Arc.HP_BP_MP, HP, BP, MP, 0);
        	addArcFeature(code | c, fv);
        	//addArcFeature(code | f, fv);
        	for (int i = 0; i < typoVecDim; ++i)
        		addArcFeature(code | f[i], v[i], fv);
    	}

		if (inst.wordVecIds[h] >= 0) {
			double[] v2 = wv.getWordVec(inst.lang, inst.wordVecIds[h]);
			for (int i = 0; i < v2.length; ++i) {
				code = createArcCodeWP(Arc.HEAD_EMB, i + 1, 0);
				addArcFeature(code | c, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j], v2[i] * v[j], fv);
				addArcFeature(code | c | attDist, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j] | attDist, v2[i] * v[j], fv);
			}
		}
		
		if (inst.wordVecIds[m] >= 0) {
			double[] v2 = wv.getWordVec(inst.lang, inst.wordVecIds[m]);
			for (int i = 0; i < v2.length; ++i) {
				code = createArcCodeWP(Arc.MOD_EMB, i + 1, 0);
				//addArcFeature(code | c, v[i], fv);
				//addArcFeature(code | f, v[i], fv);
				addArcFeature(code | c | attDist, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j] | attDist, v2[i] * v[j], fv);
			}
		}

		if (inst.transids[h] >= 0) {
			int head = inst.transids[h] + 1;
	    	code = createArcCodeWPPP(Arc.HW_HP_MP, head, HP, MP, 0);
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
	    	
	    	code = createArcCodeWPP(Arc.HW_MP, head, MP, 0);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
		}
		
		if (inst.transids[m] >= 0) {
			int mod = inst.transids[m] + 1;
	    	code = createArcCodeWPPP(Arc.MW_HP_MP, mod, HP, MP, 0);
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
	    	
	    	code = createArcCodeWPP(Arc.MW_HP, mod, HP, 0);
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
			
		}
    }
    
    public void addBareFeatures (DependencyInstance inst, int h, int m, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int dist = getBinnedDistance(Math.abs(h - m)) + 1;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;

    	code = createArcCodeP(Arc.DIST, 0);
    	addArcFeature(code | dist, fv);
    	
    	code = createArcCodeP(Arc.B_HP, HP);
    	addArcFeature(code, fv);
    	addArcFeature(code | dist, fv);

    	code = createArcCodeP(Arc.B_MP, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | dist, fv);

    	code = createArcCodePP(Arc.B_HP_MP, HP, MP);
    	addArcFeature(code, fv);
    	addArcFeature(code | dist, fv);

    	if (inst.wordVecIds[h] >= 0) {
			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[h]);
			for (int i = 0; i < v.length; ++i) {
				code = createArcCodeW(Arc.B_HEAD_EMB, i + 1);
				addArcFeature(code, v[i], fv);
				addArcFeature(code | dist, v[i], fv);
			}
		}
		
//		if (inst.wordVecIds[m] >= 0) {
//			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[m]);
//			for (int i = 0; i < v.length; ++i) {
//				code = createArcCodeW(Arc.B_MOD_EMB, i + 1);
//				//addArcFeature(code, v[i], fv);
//				addArcFeature(code | dist, v[i], fv);
//			}
//		}
    }
    
    public void addSelectiveFeatures (DependencyInstance inst, int h, int m, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		int[] feature = typo.getFeature(inst.lang);
		
		int dir = h > m ? 1 : 2;
		//int dir = getBinnedDistance(h - m) + 1;
		
		int HP = pos[h];
		int MP = pos[m];

	    if (HP == POS_ADP && MP == POS_NOUN) {
			    code = createArcCodeP(Arc.ADP_NOUN, feature[TypoFeatureType.Prep.ordinal()] + 1);
		    	addArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_ADP && MP == POS_PRON) {
			    code = createArcCodeP(Arc.ADP_PRON, feature[TypoFeatureType.Prep.ordinal()] + 1);
		    	addArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_NOUN && MP == POS_NOUN) {
			    code = createArcCodeP(Arc.GEN, feature[TypoFeatureType.Gen.ordinal()] + 1);
		    	addArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_NOUN && MP == POS_ADJ) {
			    code = createArcCodeP(Arc.ADJ, feature[TypoFeatureType.Adj.ordinal()] + 1);
		    	addArcFeature(code | dir, fv);
		    }
		   
    }
    
    public void addSupervisedFeatures(FeatureVector fv, DependencyInstance inst, 
    		int h, int m, int attDist, int label) 
    {
    	int tid = label << 4;
    	
    	long code = 0; 			// feature code
    	
    	int[] forms = inst.formids, postags = inst.postagids;
    	

    	code = createArcCodeW(Arc.L_CORE_HEAD_WORD, forms[h] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	    	    	
    	code = createArcCodeW(Arc.L_CORE_MOD_WORD, forms[m] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWW(Arc.L_HW_MW, forms[h] + 1, forms[m] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeP(Arc.L_CORE_HEAD_POS, postags[h] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeP(Arc.L_CORE_MOD_POS, postags[m] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodePP(Arc.L_HP_MP, postags[h] + 1, postags[m] + 1) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	int[] pos = inst.postagids;
    	int pHead = pos[h] + 1;
    	int pMod = pos[m] + 1;
    	int pHeadLeft = (h > 0 ? pos[h-1] : TOKEN_START) + 1;    	
    	int pModRight = (m < pos.length-1 ? pos[m+1] : TOKEN_END) + 1;
    	int pHeadRight = (h < pos.length-1 ? pos[h+1] : TOKEN_END) + 1;
    	int pModLeft = (m > 0 ? pos[m-1] : TOKEN_START) + 1;
    	
    	// feature posR posMid posL
    	int small = h < m ? h : m;
    	int large = h > m ? h : m;
    	for(int i = small+1; i < large; i++) {    		
    		code = createArcCodePPP(Arc.L_HP_BP_MP, pHead, pos[i], pMod) | tid;
    		addArcFeature(code, fv);
    		addArcFeature(code | attDist, fv);
    	}
    	
    	// feature posL-1 posL posR posR+1
    	code = createArcCodePPPP(Arc.L_HPp_HP_MP_MPn, pHeadLeft, pHead, pMod, pModRight) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HP_MP_MPn, pHead, pMod, pModRight) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_HP_MP, pHeadLeft, pHead, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_MP_MPn, pHeadLeft, pMod, pModRight) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_HP_MPn, pHeadLeft, pHead, pModRight) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
    	
    	// feature posL posL+1 posR-1 posR
		code = createArcCodePPPP(Arc.L_HP_HPn_MPp_MP, pHead, pHeadRight, pModLeft, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_MPp_MP, pHead, pModLeft, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_HPn_MP, pHead, pHeadRight, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HPn_MPp_MP, pHeadRight, pModLeft, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_HPn_MPp, pHead, pHeadRight, pModLeft) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
    	
		// feature posL-1 posL posR-1 posR
		// feature posL posL+1 posR posR+1
		code = createArcCodePPPP(Arc.L_HPp_HP_MPp_MP, pHeadLeft, pHead, pModLeft, pMod) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPPP(Arc.L_HP_HPn_MP_MPn, pHead, pHeadRight, pMod, pModRight) | tid;
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
   	     	
		int head = inst.formids[h] + 1, headP = inst.postagids[h] + 1;
		int mod = inst.formids[m] + 1, modP = inst.postagids[m] + 1;
    	code = createArcCodeWWPP(Arc.L_HW_MW_HP_MP, head, mod, headP, modP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWPP(Arc.L_MW_HP_MP, mod, headP, modP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWPP(Arc.L_HW_HP_MP, head, headP, modP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_MW_HP, mod, headP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_HW_MP, head, modP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	    	
    	code = createArcCodeWP(Arc.L_HW_HP, head, headP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_MW_MP, mod, modP) | tid;
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    }

    public FeatureVector createArcLabelFeatures(DependencyInstance inst, int h, int m, int label) 
    {
    	FeatureVector fv = new FeatureVector(numArcFeats);
    	if (!options.learnLabel) 
    		return fv;
    	
    	//if (options.useSupervised && inst.lang == options.targetLang) {
    	//	addSupervisedFeatures(fv, inst, h, m, getBinnedDistance(h - m) + 1, label + 1);
    	//}

    	if (options.direct)
    		addDelexicalFeatures(inst, h, m, label + 1, fv);
    	else {
    		addDelexicalCFFeatures(inst, h, m, label + 1, fv);
    		addBareFeatures(inst, h, m, label + 1, fv);
    		addSelectiveFeatures(inst, h, m, label + 1, fv);
    	}
    	
    	return fv;
    }
    
    public void addDelexicalFeatures(DependencyInstance inst, int h, int m, int label, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int tid = label << 4;
		int attDist = getBinnedDistance(h - m) + 1;
		int n = inst.length;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;
		int HPp = (h > 0 ? pos[h - 1] : TOKEN_START) + 1;
		int HPn = (h < n - 1 ? pos[h + 1] : TOKEN_END) + 1;
		int MPp = (m > 0 ? pos[m - 1] : TOKEN_START) + 1;
		int MPn = (m < n - 1 ? pos[m + 1] : TOKEN_END) + 1;
		
    	code = createArcCodeP(Arc.HP, HP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodeP(Arc.MP, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePP(Arc.HP_MP, HP, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HPp_HP_MP, HPp, HP, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_HPn_MP, HP, HPn, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_MPp_MP, HP, MPp, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPP(Arc.HP_MP_MPn, HP, MP, MPn) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodeP(Arc.ATTDIST, 0) | tid;
    	addLabeledArcFeature(code | attDist, fv);
    	
    	code = createArcCodePPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	code = createArcCodePPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | attDist, fv);

    	int large = Math.max(h, m);
    	int small = Math.min(h, m);
    	
    	for (int i = small + 1; i < large; ++i) {
    		int BP = pos[i] + 1;
        	code = createArcCodePPP(Arc.HP_BP_MP, HP, BP, MP) | tid;
        	addLabeledArcFeature(code, fv);
    	}


		if (inst.wordVecIds[h] >= 0) {
			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[h]);
			for (int i = 0; i < v.length; ++i) {
				code = createArcCodeW(Arc.HEAD_EMB, i + 1) | tid;
				addLabeledArcFeature(code, v[i], fv);
				addLabeledArcFeature(code | attDist, v[i], fv);
			}
		}
		
		if (inst.wordVecIds[m] >= 0) {
			double[] v = wv.getWordVec(inst.lang, inst.wordVecIds[m]);
			for (int i = 0; i < v.length; ++i) {
				code = createArcCodeW(Arc.MOD_EMB, i + 1) | tid;
				addLabeledArcFeature(code, v[i], fv);
				addLabeledArcFeature(code | attDist, v[i], fv);
			}
		}

		if (inst.transids[h] >= 0) {
			int head = inst.transids[h] + 1;
	    	code = createArcCodeWPP(Arc.HW_HP_MP, head, HP, MP) | tid;
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
	    	
	    	code = createArcCodeWP(Arc.HW_MP, head, MP) | tid;
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
		}
		
		if (inst.transids[m] >= 0) {
			int mod = inst.transids[m] + 1;
	    	code = createArcCodeWPP(Arc.MW_HP_MP, mod, HP, MP) | tid;
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
	    	
	    	code = createArcCodeWP(Arc.MW_HP, mod, HP) | tid;
			addLabeledArcFeature(code, fv);
			addLabeledArcFeature(code | attDist, fv);
			
		}
    }
    
    public void addDelexicalCFFeatures(DependencyInstance inst, int h, int m, int label, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int tid = label << 4;
		int attDist = getBinnedDistance(h - m) + 1;
		int c = ((typo.getClass(inst.lang) + 1) << numArcFeatBits) << flagBits;
		
		int typoVecDim = 1;
		int[] f = new int[typoVecDim];
		double[] v = new double[typoVecDim];
		f[0] = ((typo.getFamily(inst.lang) + typo.classNum + 1) << numArcFeatBits) << flagBits;
		v[0] = 1.0;
		
//		int typoVecDim = options.typoVecDim;
//		int[] f = new int[options.typoVecDim];
//		double[] v = typo.getTypoVec(inst.lang);
//		for (int i = 0; i < options.typoVecDim; ++i) {
//			f[i] = ((i + typo.classNum + 1) << numArcFeatBits) << flagBits;
//		}
		
		int n = inst.length;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;
		int HPp = (h > 0 ? pos[h - 1] : TOKEN_START) + 1;
		int HPn = (h < n - 1 ? pos[h + 1] : TOKEN_END) + 1;
		int MPp = (m > 0 ? pos[m - 1] : TOKEN_START) + 1;
		int MPn = (m < n - 1 ? pos[m + 1] : TOKEN_END) + 1;
		
    	code = createArcCodePP(Arc.HP, HP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	//addLabeledArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePP(Arc.MP, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	//addLabeledArcFeature(code | f, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPP(Arc.HP_MP, HP, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HPp_HP_MP, HPp, HP, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_HPn_MP, HP, HPn, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_MPp_MP, HP, MPp, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPP(Arc.HP_MP_MPn, HP, MP, MPn, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePP(Arc.ATTDIST, 0, 0) | tid;
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	code = createArcCodePPPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP, 0) | tid;
    	addLabeledArcFeature(code | c, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i], v[i], fv);
    	//addLabeledArcFeature(code | f, fv);
    	addLabeledArcFeature(code | c | attDist, fv);
    	//addLabeledArcFeature(code | f | attDist, fv);
    	for (int i = 0; i < typoVecDim; ++i)
    		addArcFeature(code | f[i] | attDist, v[i], fv);

    	int large = Math.max(h, m);
    	int small = Math.min(h, m);
    	
    	for (int z = small + 1; z < large; ++z) {
    		int BP = pos[z] + 1;
        	code = createArcCodePPPP(Arc.HP_BP_MP, HP, BP, MP, 0) | tid;
        	addLabeledArcFeature(code | c, fv);
        	//addLabeledArcFeature(code | f, fv);
        	for (int i = 0; i < typoVecDim; ++i)
        		addArcFeature(code | f[i], v[i], fv);
   		}
   		
		if (inst.wordVecIds[h] >= 0) {
			double[] v2 = wv.getWordVec(inst.lang, inst.wordVecIds[h]);
			for (int i = 0; i < v2.length; ++i) {
				code = createArcCodeWP(Arc.HEAD_EMB, i + 1, 0) | tid;
				addArcFeature(code | c, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j], v2[i] * v[j], fv);
				addArcFeature(code | c | attDist, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j] | attDist, v2[i] * v[j], fv);
			}
		}
		
		if (inst.wordVecIds[m] >= 0) {
			double[] v2 = wv.getWordVec(inst.lang, inst.wordVecIds[m]);
			for (int i = 0; i < v2.length; ++i) {
				code = createArcCodeWP(Arc.MOD_EMB, i + 1, 0) | tid;
				addArcFeature(code | c, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j], v2[i] * v[j], fv);
				addArcFeature(code | c | attDist, v2[i], fv);
		    	for (int j = 0; j < typoVecDim; ++j)
		    		addArcFeature(code | f[j] | attDist, v2[i] * v[j], fv);
			}
		}
		
		if (inst.transids[h] >= 0) {
			int head = inst.transids[h] + 1;
	    	code = createArcCodeWPPP(Arc.HW_HP_MP, head, HP, MP, 0) | tid;
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
	    	
	    	code = createArcCodeWPP(Arc.HW_MP, head, MP, 0) | tid;
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
		}
		
		if (inst.transids[m] >= 0) {
			int mod = inst.transids[m] + 1;
	    	code = createArcCodeWPPP(Arc.MW_HP_MP, mod, HP, MP, 0) | tid;
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
	    	
	    	code = createArcCodeWPP(Arc.MW_HP, mod, HP, 0) | tid;
	    	addLabeledArcFeature(code | c, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i], v[i], fv);
	    	addLabeledArcFeature(code | c | attDist, fv);
	    	for (int i = 0; i < typoVecDim; ++i)
	    		addLabeledArcFeature(code | f[i] | attDist, v[i], fv);
			
		}
    }
    
    public void addBareFeatures (DependencyInstance inst, int h, int m, int label, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		
		int tid = label << 4;
		int dist = getBinnedDistance(Math.abs(h - m)) + 1;
		
		int HP = pos[h] + 1;
		int MP = pos[m] + 1;

    	code = createArcCodeP(Arc.DIST, 0) | tid;
    	addLabeledArcFeature(code | dist, fv);
    	
    	code = createArcCodeP(Arc.B_HP, HP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | dist, fv);

    	code = createArcCodeP(Arc.B_MP, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | dist, fv);

    	code = createArcCodePP(Arc.B_HP_MP, HP, MP) | tid;
    	addLabeledArcFeature(code, fv);
    	addLabeledArcFeature(code | dist, fv);
    	
    }
    
    public void addSelectiveFeatures (DependencyInstance inst, int h, int m, int label, FeatureVector fv) {
	    long code = 0;
		int[] pos = inst.postagids;
		int[] feature = typo.getFeature(inst.lang);
		
		int tid = label << 4;
		int dir = h > m ? 1 : 2;
		//int dir = getBinnedDistance(h - m) + 1;
		
		int HP = pos[h];
		int MP = pos[m];
		
	    if (HP == POS_VERB && MP == POS_NOUN && 
	    	(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS)) {
		    code = createArcCodeP(Arc.SV_NOUN, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
		    addLabeledArcFeature(code | dir, fv);
	    }
    	
	    if (HP == POS_VERB && MP == POS_PRON && 
		    	(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS)) {
	    	code = createArcCodeP(Arc.SV_PRON, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
	    	addLabeledArcFeature(code | dir, fv);
	    }
	    	
	    if (HP == POS_VERB && MP == POS_NOUN && 
		    	(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ)) {
	    	code = createArcCodeP(Arc.VO_NOUN, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
	    	addLabeledArcFeature(code | dir, fv);
	    }
	    	
	    if (HP == POS_VERB && MP == POS_PRON && 
		    	(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ)) {
	    	code = createArcCodeP(Arc.VO_PRON, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
	    	addLabeledArcFeature(code | dir, fv);
	    }
	    
	    if (HP == POS_ADP && MP == POS_NOUN) {
			    code = createArcCodeP(Arc.ADP_NOUN, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
			    addLabeledArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_ADP && MP == POS_PRON) {
			    code = createArcCodeP(Arc.ADP_PRON, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
			    addLabeledArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_NOUN && MP == POS_NOUN) {
			    code = createArcCodeP(Arc.GEN, feature[TypoFeatureType.Gen.ordinal()] + 1) | tid;
			    addLabeledArcFeature(code | dir, fv);
		    }
	    	
	    if (HP == POS_NOUN && MP == POS_ADJ) {
			    code = createArcCodeP(Arc.ADJ, feature[TypoFeatureType.Adj.ordinal()] + 1) | tid;
			    addLabeledArcFeature(code | dir, fv);
		    }
		  
    }
    
    public void addSupervisedFeatures(FeatureVector fv, DependencyInstance inst, 
    		int h, int m, int attDist) 
    {
    	
    	long code = 0; 			// feature code
    	
    	int[] forms = inst.formids, postags = inst.postagids;
    	

    	code = createArcCodeW(Arc.L_CORE_HEAD_WORD, forms[h] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	    	    	
    	code = createArcCodeW(Arc.L_CORE_MOD_WORD, forms[m] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWW(Arc.L_HW_MW, forms[h] + 1, forms[m] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeP(Arc.L_CORE_HEAD_POS, postags[h] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeP(Arc.L_CORE_MOD_POS, postags[m] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodePP(Arc.L_HP_MP, postags[h] + 1, postags[m] + 1);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	int[] pos = inst.postagids;
    	int pHead = pos[h] + 1;
    	int pMod = pos[m] + 1;
    	int pHeadLeft = (h > 0 ? pos[h-1] : TOKEN_START) + 1;    	
    	int pModRight = (m < pos.length-1 ? pos[m+1] : TOKEN_END) + 1;
    	int pHeadRight = (h < pos.length-1 ? pos[h+1] : TOKEN_END) + 1;
    	int pModLeft = (m > 0 ? pos[m-1] : TOKEN_START) + 1;
    	
    	// feature posR posMid posL
    	int small = h < m ? h : m;
    	int large = h > m ? h : m;
    	for(int i = small+1; i < large; i++) {    		
    		code = createArcCodePPP(Arc.L_HP_BP_MP, pHead, pos[i], pMod);
    		addArcFeature(code, fv);
    		addArcFeature(code | attDist, fv);
    	}
    	
    	// feature posL-1 posL posR posR+1
    	code = createArcCodePPPP(Arc.L_HPp_HP_MP_MPn, pHeadLeft, pHead, pMod, pModRight);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HP_MP_MPn, pHead, pMod, pModRight);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_HP_MP, pHeadLeft, pHead, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_MP_MPn, pHeadLeft, pMod, pModRight);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
    	code = createArcCodePPP(Arc.L_HPp_HP_MPn, pHeadLeft, pHead, pModRight);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
    	
    	// feature posL posL+1 posR-1 posR
		code = createArcCodePPPP(Arc.L_HP_HPn_MPp_MP, pHead, pHeadRight, pModLeft, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_MPp_MP, pHead, pModLeft, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_HPn_MP, pHead, pHeadRight, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HPn_MPp_MP, pHeadRight, pModLeft, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPP(Arc.L_HP_HPn_MPp, pHead, pHeadRight, pModLeft);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
    	
		// feature posL-1 posL posR-1 posR
		// feature posL posL+1 posR posR+1
		code = createArcCodePPPP(Arc.L_HPp_HP_MPp_MP, pHeadLeft, pHead, pModLeft, pMod);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
		
		code = createArcCodePPPP(Arc.L_HP_HPn_MP_MPn, pHead, pHeadRight, pMod, pModRight);
		addArcFeature(code, fv);
		addArcFeature(code | attDist, fv);
   	     	
		int head = inst.formids[h] + 1, headP = inst.postagids[h] + 1;
		int mod = inst.formids[m] + 1, modP = inst.postagids[m] + 1;
    	code = createArcCodeWWPP(Arc.L_HW_MW_HP_MP, head, mod, headP, modP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWPP(Arc.L_MW_HP_MP, mod, headP, modP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWPP(Arc.L_HW_HP_MP, head, headP, modP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_MW_HP, mod, headP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_HW_MP, head, modP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	    	
    	code = createArcCodeWP(Arc.L_HW_HP, head, headP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    	
    	code = createArcCodeWP(Arc.L_MW_MP, mod, modP);
    	addArcFeature(code, fv);
    	addArcFeature(code | attDist, fv);
    }

    /***
     * generate code
     */
    /*
    public final int getBinnedDistance(int x) {
    	// x = h-c, 0-6: left, 7-13:right
    	
    	int flag = 0;
    	int add = 0;
    	if (x < 0) {
    		x = -x;
    		//flag = 8;
    		add = 7;
    	}
    	if (x > 10)          // x > 10
    		flag |= 0x7;
    	else if (x > 5)		 // x = 6 .. 10
    		flag |= 0x6;
    	else
    		flag |= x;   	 // x = 1 .. 5
    	return flag+add-1;	// zero based
    }
    */
    public final int getBinnedDistance(int x) {
    	// x = h-c, 0-4: left, 5-9:right
    	
    	int flag = 0;
    	int add = 0;
    	if (x < 0) {
    		x = -x;
    		add = 5;
    	}
    	if (x >= 5)          // x >= 5
    		flag |= 0x5;
    	else
    		flag |= x;   	 // x = 1 .. 4
    	return flag+add-1;	// zero based
    }
    
    public int getPPCode(int p0, int p1) {
    	return p0 * posNum + p1;
    }

	public long createArcCodeP(FeatureTemplate.Arc temp, long x)
	{
		return ((x << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodePP(FeatureTemplate.Arc temp, long x, long y)
	{
		return ((((x << tagNumBits) | y) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodePPP(FeatureTemplate.Arc temp, long x, long y, long z)
	{
		return ((((((x << tagNumBits) | y) << tagNumBits) | z ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodePPPP(FeatureTemplate.Arc temp, long x, long y, long z, long w)
	{
		return ((((((((x << tagNumBits) | y) << tagNumBits) | z ) << tagNumBits) | w ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodePPPPP(FeatureTemplate.Arc temp, long x, long y, long z, long w, long v)
	{
		return ((((((((((x << tagNumBits) | y) << tagNumBits) | z ) << tagNumBits) | w ) << tagNumBits) | v ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}

    public final long createArcCodeW(FeatureTemplate.Arc temp, long x) {
    	return ((x << numArcFeatBits) | temp.ordinal()) << flagBits;
    }
    
    public final long createArcCodeWP(FeatureTemplate.Arc temp, long x, long y) {
    	return ((((x << tagNumBits) | y) << numArcFeatBits) | temp.ordinal()) << flagBits;
    }
    
    public final long createArcCodeWW(FeatureTemplate.Arc temp, long x, long y) {
    	return ((((x << wordNumBits) | y) << numArcFeatBits) | temp.ordinal()) << flagBits;
    }
    
	public long createArcCodeWPP(FeatureTemplate.Arc temp, long x, long y, long z)
	{
		return ((((((x << tagNumBits) | y) << tagNumBits) | z ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodeWPPP(FeatureTemplate.Arc temp, long x, long y, long z, long w)
	{
		return ((((((((x << tagNumBits) | y) << tagNumBits) | z ) << tagNumBits) | w ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
	public long createArcCodeWWPP(FeatureTemplate.Arc temp, long x, long y, long z, long w)
	{
		return ((((((((x << wordNumBits) | y) << tagNumBits) | z ) << tagNumBits) | w ) << numArcFeatBits) | temp.ordinal()) << flagBits;
	}
	
    /*	
	public void addArcFeature(long code, FeatureVector fv)
	{
		int id = arcAlphabet.lookupIndex(code, numArcFeats);
		if (id >= 0) {
			fv.addEntry(id, 1.0);
			if (id == numArcFeats) ++numArcFeats;
		}
	}

	public void addLabeledArcFeature(long code, FeatureVector fv)
	{
		int id = arcAlphabet.lookupIndex(code, numArcFeats);
		if (id >= 0) {
			fv.addEntry(id, 1.0);
			if (id == numArcFeats) ++numArcFeats;
		}
	}
	*/
	
    public final void addArcFeature(long code, FeatureVector mat) {
    	long hash = (code ^ (code&0xffffffff00000000L) >>> 32)*31;
    	int id = (int)((hash < 0 ? -hash : hash) % numArcFeats);
    	//int id = ((hash ^ (hash >> 31)) - (hash >> 31)) % 115911564;
    	mat.addEntry(id, 1.0);
    	if (!stoppedGrowth) {
    		featureHashSet.add(code);
    		featureIDSet.add(id);
    	}
    }
    
    public final void addArcFeature(long code, double value, FeatureVector mat) {
    	long hash = (code ^ (code&0xffffffff00000000L) >>> 32)*31;
    	int id = (int)((hash < 0 ? -hash : hash) % numArcFeats);
    	//int id = ((hash ^ (hash >> 31)) - (hash >> 31)) % 115911564;    	
    	mat.addEntry(id, value);
    	if (!stoppedGrowth) {
    		featureHashSet.add(code);
    		featureIDSet.add(id);
    	}
    }
    
    public final void addLabeledArcFeature(long code, FeatureVector mat) {
    	long hash = (code ^ (code&0xffffffff00000000L) >>> 32)*31;
    	int id = (int)((hash < 0 ? -hash : hash) % numLabeledArcFeats);
    	//int id = ((hash ^ (hash >> 31)) - (hash >> 31)) % 115911564;    	
    	mat.addEntry(id, 1.0);
    	if (!stoppedGrowth) {
    		featureHashSet.add(code);
    		featureIDSet.add(id);
    	}
    }
    
    public final void addLabeledArcFeature(long code, double value, FeatureVector mat) {
    	long hash = (code ^ (code&0xffffffff00000000L) >>> 32)*31;
    	int id = (int)((hash < 0 ? -hash : hash) % numLabeledArcFeats);
    	//int id = ((hash ^ (hash >> 31)) - (hash >> 31)) % 115911564;    	
    	mat.addEntry(id, value);
    	if (!stoppedGrowth) {
    		featureHashSet.add(code);
    		featureIDSet.add(id);
    	}
    }
    
    // fill tensor parameters
    public int hashcode2int(long code) {
    	long hash = (code ^ (code&0xffffffff00000000L) >>> 32)*31;
    	int id = (int)((hash < 0 ? -hash : hash) % numArcFeats);
    	return id;
    }
    
    private final long extractArcTemplateCode(long code) {
    	return (code >> flagBits) & ((1 << numArcFeatBits)-1);
    }
    
    private final long extractDistanceCode(long code) {
    	return code & 15;
    }
    
    private final long extractLabelCode(long code) {
    	return (code >> 4) & ((1 << depNumBits)-1);
    }
    
    private final void extractArcCodeP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[0] = (int) (code & ((1 << tagNumBits)-1));
    }
    
    private final void extractArcCodePP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << tagNumBits)-1));
    }
    
    private final void extractArcCodePPP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[2] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << tagNumBits)-1));
    }
    
    private final void extractArcCodePPPP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[3] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[2] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << tagNumBits)-1));
    }
    
    private final void extractArcCodePPPPP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[4] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[3] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[2] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << tagNumBits)-1));
    }
    
    private final void extractArcCodeW(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[0] = (int) (code & ((1 << wordNumBits)-1));
    }
    
    private final void extractArcCodeWP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << wordNumBits)-1));
    }

    private final void extractArcCodeWPP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[2] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << wordNumBits)-1));
    }

    private final void extractArcCodeWPPP(long code, int[] x) {
    	code = (code >> flagBits) >> numArcFeatBits;
	    x[3] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[2] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[1] = (int) (code & ((1 << tagNumBits)-1));
	    code = code >> tagNumBits;
	    x[0] = (int) (code & ((1 << wordNumBits)-1));
    }

    public void fillMultiwayParameters(LowRankParam tensor, Parameters params) {
    	long[] codes = featureHashSet.toArray();
    	//long[] codes = arcAlphabet.toArray();
    	System.out.println(codes.length);
    	int[] x = new int[4];
    	ParameterNode pn = params.pn;
		ParameterNode hpn = pn.node[0];
		ParameterNode mpn = pn.node[1];
		ParameterNode hcpn = pn.node[2];
		ParameterNode mcpn = pn.node[3];
		ParameterNode dpn = pn.node[4];
		ParameterNode lpn = options.learnLabel ? pn.node[5] : null;
    	
    	for (long code : codes) {
    		
    		//int id = arcAlphabet.lookupIndex(code);
    		int id = hashcode2int(code);
    		if (id < 0) continue;
    		
    		int binDist = (int) extractDistanceCode(code);
    		binDist = binDist == 0 ? 0 : dpn.featureBias[0] + (binDist - 1);
    		
    		int temp = (int) extractArcTemplateCode(code);
    		
    		int label = (int) extractLabelCode(code);
    		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    		
    		int head = 0, mod = 0, hc = 0, mc = 0;
        	
    		//code = createArcCodeP(Arc.ATTDIST, 0) | tid;
    		if (temp == Arc.ATTDIST.ordinal()) {
    			extractArcCodeP(code, x);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    		}
        	
        	//code = createArcCodeP(Arc.HP, HP) | tid;
    		else if (temp == Arc.HP.ordinal()) {
    			extractArcCodeP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    		}

        	//code = createArcCodeP(Arc.MP, MP) | tid;
    		else if (temp == Arc.MP.ordinal()) {
    			extractArcCodeP(code, x);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    			hc = 0;
    			mc = 0;
    		}

        	//code = createArcCodePP(Arc.HP_MP, HP, MP) | tid;
    		else if (temp == Arc.HP_MP.ordinal()) {
    			extractArcCodePP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    		}

        	//code = createArcCodePPP(Arc.HPp_HP_MP, HPp, HP, MP) | tid;
    		else if (temp == Arc.HPp_HP_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[0] + (x[0] - 1);
    			mc = 0;
    		}

        	//code = createArcCodePPP(Arc.HP_HPn_MP, HP, HPn, MP) | tid;
    		else if (temp == Arc.HP_HPn_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[1] + (x[1] - 1);
    			mc = 0;
    		}

        	//code = createArcCodePPP(Arc.HP_MPp_MP, HP, MPp, MP) | tid;
    		else if (temp == Arc.HP_MPp_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[0] + (x[1] - 1);
    		}

        	//code = createArcCodePPP(Arc.HP_MP_MPn, HP, MP, MPn) | tid;
    		else if (temp == Arc.HP_MP_MPn.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[1] + (x[2] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn) | tid;
    		else if (temp == Arc.HPp_HP_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[0] + (x[0] - 1);
    			mc = mcpn.featureBias[1] + (x[3] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn) | tid;
    		else if (temp == Arc.HP_HPn_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[1] + (x[1] - 1);
    			mc = mcpn.featureBias[1] + (x[3] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP) | tid;
    		else if (temp == Arc.HP_HPn_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[1] + (x[1] - 1);
    			mc = mcpn.featureBias[0] + (x[2] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP) | tid;
    		else if (temp == Arc.HPp_HP_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[0] + (x[0] - 1);
    			mc = mcpn.featureBias[0] + (x[2] - 1);
    		}
    		
    		else {
    			continue;
    		}

   			double value = params.params[id];
   			//if (hpn.isActive[head] && mpn.isActive[mod] && hcpn.isActive[hc] && mcpn.isActive[mc] && dpn.isActive[binDist]
   			//		&& (!options.learnLabel || lpn.isActive[label]))
   			Utils.Assert(hpn.isActive[head]);
   			Utils.Assert(mpn.isActive[mod]);
   			Utils.Assert(hcpn.isActive[hc]);
   			Utils.Assert(mcpn.isActive[mc]);
   			Utils.Assert(dpn.isActive[binDist]);
   			Utils.Assert(!options.learnLabel || lpn.isActive[label]);
   			if (Math.abs(value) > 1e-8) {
   				label = options.learnLabel ? label : -1;
   				tensor.putEntry(head, mod, hc, mc, binDist, label, value);
   			}
    	}
    }

    public void fillThreewayParameters(LowRankParam tensor, Parameters params) {
    	long[] codes = featureHashSet.toArray();
    					//arcAlphabet.toArray();
    	System.out.println(codes.length);
    	int[] x = new int[4];
    	ParameterNode pn = params.pn;
    	ParameterNode hpn = pn.node[0];
    	ParameterNode mpn = pn.node[1];
    	ParameterNode dpn = pn.node[2];
    	ParameterNode lpn = options.learnLabel ? pn.node[3] : null;
    	
    	for (long code : codes) {
    		
    		//int id = arcAlphabet.lookupIndex(code);
    		int id = hashcode2int(code);
    		if (id < 0) continue;
    		
    		int binDist = (int) extractDistanceCode(code);
    		binDist = binDist == 0 ? 0 : dpn.featureBias[0] + (binDist - 1);
    		
    		int temp = (int) extractArcTemplateCode(code);
    		
    		int label = (int) extractLabelCode(code);
    		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    		
    		int head = 0, mod = 0;
        	
    		//code = createArcCodeP(Arc.ATTDIST, 0) | tid;
    		if (temp == Arc.ATTDIST.ordinal()) {
    			extractArcCodeP(code, x);
    			head = 0;
    			mod = 0;
    		}
        	
        	//code = createArcCodeP(Arc.HP, HP) | tid;
    		else if (temp == Arc.HP.ordinal()) {
    			extractArcCodeP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    		}

        	//code = createArcCodeP(Arc.MP, MP) | tid;
    		else if (temp == Arc.MP.ordinal()) {
    			extractArcCodeP(code, x);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    		}

        	//code = createArcCodePP(Arc.HP_MP, HP, MP) | tid;
    		else if (temp == Arc.HP_MP.ordinal()) {
    			extractArcCodePP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    		}

        	//code = createArcCodePPP(Arc.HPp_HP_MP, HPp, HP, MP) | tid;
    		else if (temp == Arc.HPp_HP_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[3] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    		}

        	//code = createArcCodePPP(Arc.HP_HPn_MP, HP, HPn, MP) | tid;
    		else if (temp == Arc.HP_HPn_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[4] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    		}

        	//code = createArcCodePPP(Arc.HP_MPp_MP, HP, MPp, MP) | tid;
    		else if (temp == Arc.HP_MPp_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[3] + getPPCode(x[1] - 1, x[2] - 1);
    		}

        	//code = createArcCodePPP(Arc.HP_MP_MPn, HP, MP, MPn) | tid;
    		else if (temp == Arc.HP_MP_MPn.ordinal()) {
    			extractArcCodePPP(code, x);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[4] + getPPCode(x[1] - 1, x[2] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn) | tid;
    		else if (temp == Arc.HPp_HP_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[3] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[4] + getPPCode(x[2] - 1, x[3] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn) | tid;
    		else if (temp == Arc.HP_HPn_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[4] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[4] + getPPCode(x[2] - 1, x[3] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP) | tid;
    		else if (temp == Arc.HP_HPn_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[4] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[3] + getPPCode(x[2] - 1, x[3] - 1);
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP) | tid;
    		else if (temp == Arc.HPp_HP_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			head = hpn.featureBias[3] + getPPCode(x[0] - 1, x[1] - 1);
    			mod = mpn.featureBias[3] + getPPCode(x[2] - 1, x[3] - 1);
    		}
    		
    		else {
    			continue;
    		}

   			double value = params.params[id];
   			//if (hpn.isActive[head] && mpn.isActive[mod] && dpn.isActive[binDist]
   			//		&& (!options.learnLabel || lpn.isActive[label])) {
   			Utils.Assert(hpn.isActive[head]);
   			Utils.Assert(mpn.isActive[mod]);
   			Utils.Assert(dpn.isActive[binDist]);
   			Utils.Assert(!options.learnLabel || lpn.isActive[label]);
   			if (Math.abs(value) > 1e-8) {
   				label = options.learnLabel ? label : -1;
   				tensor.putEntry(head, mod, binDist, label, value);
   			}
    	}
    }

    public void fillHierarchichalParameters(LowRankParam tensor, Parameters params) {
    	long[] codes = featureHashSet.toArray();
    					//arcAlphabet.toArray();
    	System.out.println(codes.length);
    	int[] x = new int[5];
    	ParameterNode delexical = options.lexical ? params.pn.node[1] : params.pn;
		ParameterNode hcpn = delexical.node[0];
		ParameterNode mcpn = delexical.node[1];
		ParameterNode apn = options.learnLabel ? delexical.node[2] : null;
		ParameterNode lpn = options.learnLabel ? apn.node[0] : null;
		ParameterNode tpn = options.learnLabel ? apn.node[1] : delexical.node[2];
		ParameterNode hpn = tpn.node[0];
		ParameterNode mpn = tpn.node[1];
		ParameterNode dpn = tpn.node[2];
		
		ParameterNode hlpn = options.lexical ? params.pn.node[0].node[0] : null;
		ParameterNode mlpn = options.lexical ? params.pn.node[0].node[1] : null;
		int d = ParameterNode.d;

    	for (long code : codes) {
    		
    		//int id = arcAlphabet.lookupIndex(code);
    		int id = hashcode2int(code);
    		if (id < 0) continue;
    		
    		double value = params.params[id];

    		int binDist = (int) extractDistanceCode(code);
    		Utils.Assert(binDist >= 0);
    		
    		int temp = (int) extractArcTemplateCode(code);
    		
    		int label = (int) extractLabelCode(code);
    		Utils.Assert(label >= 0);
    		
    		int head = 0, mod = 0, hc = 0, mc = 0, svo = 0, t = 0;
    		int hl = 0, ml = 0;
        	
    		//code = createArcCodePP(Arc.ATTDIST, 0, cf) | tid;
    		if (temp == Arc.ATTDIST.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[1] > 0 && x[0] == 0);
    			head = 0;
    			mod = 0;
    			//hc = hcpn.featureBias[0] + (x[1] - 1);
    			//mc = mcpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}
        	
        	//code = createArcCodePP(Arc.HP, HP, cf) | tid;
    		else if (temp == Arc.HP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    			//hc = hcpn.featureBias[0] + (x[1] - 1);
    			//mc = mcpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
   		}

        	//code = createArcCodePP(Arc.MP, MP, cf) | tid;
    		else if (temp == Arc.MP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    			//hc = hcpn.featureBias[0] + (x[1] - 1);
    			//mc = mcpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPP(Arc.HP_MP, HP, MP, cf) | tid;
    		else if (temp == Arc.HP_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			//hc = hcpn.featureBias[0] + (x[2] - 1);
    			//mc = mcpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MP, HPp, HP, MP, cf) | tid;
    		else if (temp == Arc.HPp_HP_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[2] + (x[3] - 1) * posNum + (x[0] - 1);
    			//mc = mcpn.featureBias[0] + (x[3] - 1);
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MP, HP, HPn, MP, cf) | tid;
    		else if (temp == Arc.HP_HPn_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[4] + (x[3] - 1) * posNum + (x[1] - 1);
    			//mc = mcpn.featureBias[0] + (x[3] - 1);
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_MPp_MP, HP, MPp, MP, cf) | tid;
    		else if (temp == Arc.HP_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			//hc = hcpn.featureBias[0] + (x[3] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[2] + (x[3] - 1) * posNum + (x[1] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_MP_MPn, HP, MP, MPn, cf) | tid;
    		else if (temp == Arc.HP_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			//hc = hcpn.featureBias[0] + (x[3] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[4] + (x[3] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn, cf) | tid;
    		else if (temp == Arc.HPp_HP_MP_MPn.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[2] + (x[4] - 1) * posNum + (x[0] - 1);
    			mc = mcpn.featureBias[4] + (x[4] - 1) * posNum + (x[3] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn, cf) | tid;
    		else if (temp == Arc.HP_HPn_MP_MPn.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[4] + (x[4] - 1) * posNum + (x[1] - 1);
    			mc = mcpn.featureBias[4] + (x[4] - 1) * posNum + (x[3] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP, cf) | tid;
    		else if (temp == Arc.HP_HPn_MPp_MP.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[4] + (x[4] - 1) * posNum + (x[1] - 1);
    			mc = mcpn.featureBias[2] + (x[4] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP, cf) | tid;
    		else if (temp == Arc.HPp_HP_MPp_MP.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[2] + (x[4] - 1) * posNum + (x[0] - 1);
    			mc = mcpn.featureBias[2] + (x[4] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}
    		
        	//code = createArcCodeP(Arc.DIST, 0) | tid;
    		else if (temp == Arc.DIST.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] == 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < d);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}
        	
        	//code = createArcCodeP(Arc.B_HP, HP) | tid;
    		else if (temp == Arc.B_HP.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodeP(Arc.B_MP, MP) | tid;
    		else if (temp == Arc.B_MP.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePP(Arc.B_HP_MP, HP, MP) | tid;
    		else if (temp == Arc.B_HP_MP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = 0;
    		}

		    //code = createArcCodeP(Arc.SV_NOUN, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
    		else if (temp == Arc.SV_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.SV));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
    			svo = v * offset + dir * 2 + (label - 1 == LABEL_SBJ ? 0 : 1);
    			//System.out.println("SV_NOUN: " + svo + " " + value);
    			t = -1;
    			label = -1;
    			hl = 0;
    			ml = 0;
    		}
    		
	    	//code = createArcCodeP(Arc.SV_PRON, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
    		else if (temp == Arc.SV_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.SV));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
    			svo = apn.featureBias[0] + v * offset + dir * 2 + (label - 1 == LABEL_SBJ ? 0 : 1);
    			//System.out.println("SV_PRON: " + svo + " " + value);
    			t = -1;
    			label = -1;
    			hl = 0;
    			ml = 0;
    		}
    	    	
	    	//code = createArcCodeP(Arc.VO_NOUN, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
    		else if (temp == Arc.VO_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.VO));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
    			svo = apn.featureBias[1] + v * offset + dir * 2 + (label - 1 == LABEL_DOBJ ? 0 : 1);
    			//System.out.println("VO_NOUN: " + svo + " " + value);
    			t = -1;
    			label = -1;
    			hl = 0;
    			ml = 0;
    		}
    		
	    	//code = createArcCodeP(Arc.VO_PRON, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
    		else if (temp == Arc.VO_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.VO));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
    			svo = apn.featureBias[2] + v * offset + dir * 2 + (label - 1 == LABEL_DOBJ ? 0 : 1);
    			//System.out.println("VO_PRON: " + svo + " " + value);
    			t = -1;
    			label = -1;
    			hl = 0;
    			ml = 0;
    		}
    		
		    //code = createArcCodeP(Arc.ADP_NOUN, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADP_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Prep));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		svo = -1;
    			t = v * offset + dir;
    			//System.out.println("PREP_NOUN: " + t + " " + value);
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.ADP_PRON, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADP_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Prep));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		svo = -1;
    			t = tpn.featureBias[0] + v * offset + dir;
    			//System.out.println("PREP_PRON: " + t + " " + value);
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.GEN, feature[TypoFeatureType.Gen.ordinal()] + 1) | tid;
    		else if (temp == Arc.GEN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Gen));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		svo = -1;
    			t = tpn.featureBias[1] + v * offset + dir;
    			//System.out.println("GEN: " + t + " " + value);
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.ADJ, feature[TypoFeatureType.Adj.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADJ.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Adj));
    			head = -1;
    			mod = -1;
    			hc = 0;
    			mc = 0;
    			binDist = -1;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		svo = -1;
    			t = tpn.featureBias[2] + v * offset + dir;
    			//System.out.println("ADJ: " + t + " " + value);
    			hl = 0;
    			ml = 0;
    		}

    		//code = createArcCodeWP(Arc.HEAD_EMB, i, 0);
    		else if (temp == Arc.HEAD_EMB.ordinal()) {
    			extractArcCodeWP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			//Utils.Assert(label == 0);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		//label = 0;
    			label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = hlpn.featureBias[0] + (x[0] - 1);
    			ml = 0;
    		}
    		
    		//code = createArcCodeWP(Arc.MOD_EMB, i, 0);
    		else if (temp == Arc.MOD_EMB.ordinal()) {
    			extractArcCodeWP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			Utils.Assert(/*label == 0 && */binDist >= 0);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		//label = 0;
    			label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = mlpn.featureBias[0] + (x[0] - 1);
    		}
    		
    		//code = createArcCodeW(Arc.B_HEAD_EMB, i);
    		else if (temp == Arc.B_HEAD_EMB.ordinal()) {
				extractArcCodeW(code, x);
				Utils.Assert(x[0] > 0);
				Utils.Assert(label == 0 && binDist - 1 < d);
				head = 0;
				mod = 0;
				hc = 0;
				mc = 0;
				binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
	    		label = 0;
				svo = -1;
				t = -1;
				hl = hlpn.featureBias[0] + (x[0] - 1);
				ml = 0;
    		}
    		
    		//code = createArcCodeW(Arc.B_MOD_EMB, i);
    		else if (temp == Arc.B_MOD_EMB.ordinal()) {
				extractArcCodeW(code, x);
				Utils.Assert(x[0] > 0);
				Utils.Assert(label == 0 && binDist - 1 < d && binDist >= 0);
				head = 0;
				mod = 0;
				hc = 0;
				mc = 0;
				binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
	    		label = 0;
				svo = -1;
				t = -1;
				hl = 0;
				ml = mlpn.featureBias[0] + (x[0] - 1);
    		}

	    	//code = createArcCodeWPPP(Arc.HW_HP_MP, head, HP, MP, 0);
    		else if (temp == Arc.HW_HP_MP.ordinal()) {
    			extractArcCodeWPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = hlpn.featureBias[1] + (x[0] - 1);
    			ml = 0;
    		}

	    	//code = createArcCodeWPP(Arc.HW_MP, head, MP, 0);
    		else if (temp == Arc.HW_MP.ordinal()) {
    			extractArcCodeWPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = hlpn.featureBias[1] + (x[0] - 1);
    			ml = 0;
    		}

	    	//code = createArcCodeWPPP(Arc.MW_HP_MP, mod, HP, MP, 0);
    		else if (temp == Arc.MW_HP_MP.ordinal()) {
    			extractArcCodeWPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = mlpn.featureBias[1] + (x[0] - 1);
    		}

    		//code = createArcCodeWPP(Arc.MW_HP, mod, HP, 0);
    		else if (temp == Arc.MW_HP.ordinal()) {
    			extractArcCodeWPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			svo = -1;
    			t = -1;
    			hl = 0;
    			ml = mlpn.featureBias[1] + (x[0] - 1);
    		}

    		else {
    			continue;
    		}

	    	
   			Utils.Assert(head < 0 || hpn.isActive[head]); 
   			Utils.Assert(mod < 0 || mpn.isActive[mod]); 
   			Utils.Assert(hc < 0 || hcpn.isActive[hc]); 
   			Utils.Assert(mc < 0 || mcpn.isActive[mc]); 
   			Utils.Assert(binDist < 0 || dpn.isActive[binDist]); 
   			Utils.Assert(label < 0 || !options.learnLabel || lpn.isActive[label]);
   			Utils.Assert(t < 0 || tpn.isActive[t]);
   			Utils.Assert(svo < 0 || apn.isActive[svo]);
   			label = options.learnLabel ? label : -1;
   			svo = options.learnLabel ? svo : -1;
   			//if (svo >= 0)
   			//	System.out.println("aaa");
   			if (Math.abs(value) > 1e-8)
   				tensor.putEntry(head, mod, hc, mc, binDist, label, svo, t, hl, ml, value);
    	}
    }

    public void fillTMultiwayParameters(LowRankParam tensor, Parameters params) {
    	long[] codes = featureHashSet.toArray();
    					//arcAlphabet.toArray();
    	System.out.println(codes.length);
    	int[] x = new int[5];
		ParameterNode hcpn = pn.node[2];
		ParameterNode mcpn = pn.node[3];
		ParameterNode lpn = pn.node[6];
		ParameterNode tpn = pn.node[4];
		ParameterNode hpn = pn.node[0];
		ParameterNode mpn = pn.node[1];
		ParameterNode dpn = pn.node[5];
		
		ParameterNode hlpn = options.lexical ? pn.node[7] : null;
		ParameterNode mlpn = options.lexical ? pn.node[8] : null;
		int d = ParameterNode.d;

    	for (long code : codes) {
    		
    		//int id = arcAlphabet.lookupIndex(code);
    		int id = hashcode2int(code);
    		if (id < 0) continue;
    		
    		double value = params.params[id];

    		int binDist = (int) extractDistanceCode(code);
    		Utils.Assert(binDist >= 0);
    		
    		int temp = (int) extractArcTemplateCode(code);
    		
    		int label = (int) extractLabelCode(code);
    		Utils.Assert(label >= 0);
    		
    		int head = 0, mod = 0, hc = 0, mc = 0, t = 0;
    		int hl = 0, ml = 0;
        	
    		//code = createArcCodePP(Arc.ATTDIST, 0, cf) | tid;
    		if (temp == Arc.ATTDIST.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[1] > 0 && x[0] == 0);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}
        	
        	//code = createArcCodePP(Arc.HP, HP, cf) | tid;
    		else if (temp == Arc.HP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
   		}

        	//code = createArcCodePP(Arc.MP, MP, cf) | tid;
    		else if (temp == Arc.MP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPP(Arc.HP_MP, HP, MP, cf) | tid;
    		else if (temp == Arc.HP_MP.ordinal()) {
    			extractArcCodePPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HPp_HP_MP, HPp, HP, MP, cf) | tid;
    		else if (temp == Arc.HPp_HP_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[2] + (x[3] - 1) * posNum + (x[0] - 1);
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_HPn_MP, HP, HPn, MP, cf) | tid;
    		else if (temp == Arc.HP_HPn_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[4] + (x[3] - 1) * posNum + (x[1] - 1);
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_MPp_MP, HP, MPp, MP, cf) | tid;
    		else if (temp == Arc.HP_MPp_MP.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[2] + (x[3] - 1) * posNum + (x[1] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPP(Arc.HP_MP_MPn, HP, MP, MPn, cf) | tid;
    		else if (temp == Arc.HP_MP_MPn.ordinal()) {
    			extractArcCodePPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = mcpn.featureBias[4] + (x[3] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HPp_HP_MP_MPn, HPp, HP, MP, MPn, cf) | tid;
    		else if (temp == Arc.HPp_HP_MP_MPn.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[2] + (x[4] - 1) * posNum + (x[0] - 1);
    			mc = mcpn.featureBias[4] + (x[4] - 1) * posNum + (x[3] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HP_HPn_MP_MPn, HP, HPn, MP, MPn, cf) | tid;
    		else if (temp == Arc.HP_HPn_MP_MPn.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = hcpn.featureBias[4] + (x[4] - 1) * posNum + (x[1] - 1);
    			mc = mcpn.featureBias[4] + (x[4] - 1) * posNum + (x[3] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HP_HPn_MPp_MP, HP, HPn, MPp, MP, cf) | tid;
    		else if (temp == Arc.HP_HPn_MPp_MP.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[4] + (x[4] - 1) * posNum + (x[1] - 1);
    			mc = mcpn.featureBias[2] + (x[4] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePPPPP(Arc.HPp_HP_MPp_MP, HPp, HP, MPp, MP, cf) | tid;
    		else if (temp == Arc.HPp_HP_MPp_MP.ordinal()) {
    			extractArcCodePPPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0 && x[4] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[3] - 1);
    			hc = hcpn.featureBias[2] + (x[4] - 1) * posNum + (x[0] - 1);
    			mc = mcpn.featureBias[2] + (x[4] - 1) * posNum + (x[2] - 1);
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[4] - 1)
    					: dpn.featureBias[3] + (x[4] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}
    		
        	//code = createArcCodeP(Arc.DIST, 0) | tid;
    		else if (temp == Arc.DIST.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] == 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < d);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}
        	
        	//code = createArcCodeP(Arc.B_HP, HP) | tid;
    		else if (temp == Arc.B_HP.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodeP(Arc.B_MP, MP) | tid;
    		else if (temp == Arc.B_MP.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[0] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

        	//code = createArcCodePP(Arc.B_HP_MP, HP, MP) | tid;
    		else if (temp == Arc.B_HP_MP.ordinal()) {
    			extractArcCodePP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			Utils.Assert(binDist - 1 < d);
    			head = hpn.featureBias[0] + (x[0] - 1);
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = 0;
    		}

		    //code = createArcCodeP(Arc.SV_NOUN, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
    		else if (temp == Arc.SV_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.SV));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
    			int tcode = v * offset + dir * 2 + (label - 1 == LABEL_SBJ ? 0 : 1);
    			//tcode = v;
    			t = tpn.featureBias[0] + tcode;
    			label = 0;
    			hl = 0;
    			ml = 0;
    		}
    		
	    	//code = createArcCodeP(Arc.SV_PRON, feature[TypoFeatureType.SV.ordinal()] + 1) | tid;
    		else if (temp == Arc.SV_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_SBJ || label - 1 == LABEL_SBJPASS);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.SV));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
    			int tcode = v * offset + dir * 2 + (label - 1 == LABEL_SBJ ? 0 : 1);
    			//tcode = v;
    			t = tpn.featureBias[1] + tcode;
    			label = 0;
    			hl = 0;
    			ml = 0;
    		}
    	    	
	    	//code = createArcCodeP(Arc.VO_NOUN, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
    		else if (temp == Arc.VO_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.VO));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
    			int tcode = v * offset + dir * 2 + (label - 1 == LABEL_DOBJ ? 0 : 1);
    			//tcode = v;
    			t = tpn.featureBias[2] + tcode;
    			label = 0;
    			hl = 0;
    			ml = 0;
    		}
    		
	    	//code = createArcCodeP(Arc.VO_PRON, feature[TypoFeatureType.VO.ordinal()] + 1) | tid;
    		else if (temp == Arc.VO_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			Utils.Assert(label - 1 == LABEL_DOBJ || label - 1 == LABEL_IOBJ);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2 * 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.VO));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
    			int tcode = v * offset + dir * 2 + (label - 1 == LABEL_DOBJ ? 0 : 1);
    			//tcode = v;
    			t = tpn.featureBias[3] + tcode;
    			label = 0;
    			hl = 0;
    			ml = 0;
    		}
    		
		    //code = createArcCodeP(Arc.ADP_NOUN, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADP_NOUN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Prep));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		int tcode = v * offset + dir;
        		//tcode = v;
    			t = tpn.featureBias[4] + tcode;
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.ADP_PRON, feature[TypoFeatureType.Prep.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADP_PRON.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Prep));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		int tcode = v * offset + dir;
        		//tcode = v;
    			t = tpn.featureBias[5] + tcode;
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.GEN, feature[TypoFeatureType.Gen.ordinal()] + 1) | tid;
    		else if (temp == Arc.GEN.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Gen));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		int tcode = v * offset + dir;
        		//tcode = v;
    			t = tpn.featureBias[6] + tcode;
    			hl = 0;
    			ml = 0;
    		}
    		    	
		    //code = createArcCodeP(Arc.ADJ, feature[TypoFeatureType.Adj.ordinal()] + 1) | tid;
    		else if (temp == Arc.ADJ.ordinal()) {
    			extractArcCodeP(code, x);
    			Utils.Assert(x[0] > 0);
    			Utils.Assert(binDist > 0 && binDist - 1 < 2);
    			int v = x[0] - 1;
    			int dir = binDist - 1;
    			int offset = 2;
    			Utils.Assert(v < typo.getNumberOfValues(TypoFeatureType.Adj));
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = 0;
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
        		int tcode = v * offset + dir;
        		//tcode = v;
    			t = tpn.featureBias[7] + tcode;
    			hl = 0;
    			ml = 0;
    		}

    		//code = createArcCodeWP(Arc.HEAD_EMB, i, 0);
    		else if (temp == Arc.HEAD_EMB.ordinal()) {
    			extractArcCodeWP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			//Utils.Assert(label == 0);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		//label = 0;
    			label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = hlpn.featureBias[0] + (x[0] - 1);
    			ml = 0;
    		}
    		
    		//code = createArcCodeWP(Arc.MOD_EMB, i, 0);
    		else if (temp == Arc.MOD_EMB.ordinal()) {
    			extractArcCodeWP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0);
    			Utils.Assert(/*label == 0 && */binDist >= 0);
    			head = 0;
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[1] - 1)
    					: dpn.featureBias[3] + (x[1] - 1) * 2 * d + (binDist - 1);
        		//label = 0;
    			label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = mlpn.featureBias[0] + (x[0] - 1);
    		}
    		
    		//code = createArcCodeW(Arc.B_HEAD_EMB, i);
    		else if (temp == Arc.B_HEAD_EMB.ordinal()) {
				extractArcCodeW(code, x);
				Utils.Assert(x[0] > 0);
				Utils.Assert(label == 0 && binDist - 1 < d);
				head = 0;
				mod = 0;
				hc = 0;
				mc = 0;
				binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
	    		label = 0;
				t = 0;
				hl = hlpn.featureBias[0] + (x[0] - 1);
				ml = 0;
    		}
    		
    		//code = createArcCodeW(Arc.B_MOD_EMB, i);
    		else if (temp == Arc.B_MOD_EMB.ordinal()) {
				extractArcCodeW(code, x);
				Utils.Assert(x[0] > 0);
				Utils.Assert(label == 0 && binDist - 1 < d && binDist >= 0);
				head = 0;
				mod = 0;
				hc = 0;
				mc = 0;
				binDist = binDist == 0 ? 0 : dpn.featureBias[2] + (binDist - 1);
	    		label = 0;
				t = 0;
				hl = 0;
				ml = mlpn.featureBias[0] + (x[0] - 1);
    		}

	    	//code = createArcCodeWPPP(Arc.HW_HP_MP, head, HP, MP, 0);
    		else if (temp == Arc.HW_HP_MP.ordinal()) {
    			extractArcCodeWPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = hlpn.featureBias[1] + (x[0] - 1);
    			ml = 0;
    		}

	    	//code = createArcCodeWPP(Arc.HW_MP, head, MP, 0);
    		else if (temp == Arc.HW_MP.ordinal()) {
    			extractArcCodeWPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = 0;
    			mod = mpn.featureBias[0] + (x[1] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = hlpn.featureBias[1] + (x[0] - 1);
    			ml = 0;
    		}

	    	//code = createArcCodeWPPP(Arc.MW_HP_MP, mod, HP, MP, 0);
    		else if (temp == Arc.MW_HP_MP.ordinal()) {
    			extractArcCodeWPPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0 && x[3] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = mpn.featureBias[0] + (x[2] - 1);
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[3] - 1)
    					: dpn.featureBias[3] + (x[3] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = mlpn.featureBias[1] + (x[0] - 1);
    		}

    		//code = createArcCodeWPP(Arc.MW_HP, mod, HP, 0);
    		else if (temp == Arc.MW_HP.ordinal()) {
    			extractArcCodeWPP(code, x);
    			Utils.Assert(x[0] > 0 && x[1] > 0 && x[2] > 0);
    			head = hpn.featureBias[0] + (x[1] - 1);
    			mod = 0;
    			hc = 0;
    			mc = 0;
    			binDist = binDist == 0 ? dpn.featureBias[0] + (x[2] - 1)
    					: dpn.featureBias[3] + (x[2] - 1) * 2 * d + (binDist - 1);
        		label = label == 0 ? 0 : lpn.featureBias[0] + (label - 1);
    			t = 0;
    			hl = 0;
    			ml = mlpn.featureBias[1] + (x[0] - 1);
    		}

    		else {
    			continue;
    		}

	    	
   			Utils.Assert(head < 0 || hpn.isActive[head]); 
   			Utils.Assert(mod < 0 || mpn.isActive[mod]); 
   			Utils.Assert(hc < 0 || hcpn.isActive[hc]); 
   			Utils.Assert(mc < 0 || mcpn.isActive[mc]); 
   			Utils.Assert(binDist < 0 || dpn.isActive[binDist]); 
   			Utils.Assert(label < 0 || !options.learnLabel || lpn.isActive[label]);
   			Utils.Assert(t < 0 || tpn.isActive[t]);
   			label = options.learnLabel ? label : -1;
   			if (Math.abs(value) > 1e-8)
   				tensor.putEntry(head, mod, hc, mc, binDist, label, t, hl, ml, value);
    	}
    }
}

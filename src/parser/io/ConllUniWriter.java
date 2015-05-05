package parser.io;

import java.io.*;
import parser.DependencyInstance;
import parser.DependencyPipe;
import parser.Options;

public class ConllUniWriter extends DependencyWriter {
	public ConllUniWriter(Options options, int lang, DependencyPipe pipe) {
		this.options = options;
		this.lang = lang;
		this.labels = pipe.types;
	}
	
	@Override
	public void writeInstance(DependencyInstance inst) throws IOException {
		
		//if (first) 
		//	first = false;
		//else
		//	writer.write("\n");
		
		String[] forms = inst.forms;
		String[] pos = inst.postags;
		int[] heads = inst.heads;
		int[] labelids = inst.deplbids;
		
	    // 3 eles ele pron pron-pers M|3P|NOM 4 SUBJ _ _
	    // ID FORM LEMMA COURSE-POS FINE-POS FEATURES HEAD DEPREL PHEAD PDEPREL
		for (int i = 1, N = inst.length; i < N; ++i) {
			writer.write(i + "\t");
			writer.write(forms[i] + "\t");
			writer.write("_" + "\t");
			writer.write(pos[i] + "\t");
			writer.write(pos[i] + "\t");
			writer.write("_\t");
			writer.write(heads[i] + "\t");
			writer.write((isLabeled ? labels[labelids[i]] : "_") + "\t_\t_");
			writer.write("\n");
		}
		
		writer.write("\n");
	}
}

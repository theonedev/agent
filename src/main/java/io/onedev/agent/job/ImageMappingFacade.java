package io.onedev.agent.job;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

public class ImageMappingFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String from;

	private final String to;

	public ImageMappingFacade(String from, String to) {
		this.from = from;
		this.to = to;
	}

	@Nullable
	public String map(String image) {
		var matcher = Pattern.compile(from).matcher(image);
		if (matcher.matches())
			return matcher.replaceFirst(to);
		else
			return null;
	}

	public static String map(List<ImageMappingFacade> imageMappings, String image) {
		for (var mapping: imageMappings) {
			var mappedImage = mapping.map(image);
			if (mappedImage != null)
				return mappedImage;
		}
		return image;
	}

}

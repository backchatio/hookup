module Jekyll
  class PageTocTag < Liquid::Tag
    def initialize(tag_name, args, tokens)
      @toc = Redcarpet::Markdown.new(Redcarpet::Render::HTML_TOC).render(tokens.join("\n"))
    end
    def render(context)
      @toc
    end
  end

end

Liquid::Template.register_tag('page_toc', Jekyll::PageTocTag)
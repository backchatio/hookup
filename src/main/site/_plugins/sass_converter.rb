module Jekyll
  # Sass plugin to convert .scss to .css
  # 
  # Note: This is configured to use the new css like syntax available in sass.
  require 'sass'
  require 'compass'
  class SassConverter < Converter
    safe true
    priority :low

     def matches(ext)
      ext =~ /scss/i
    end

    def output_ext(ext)
      ".css"
    end

    def convert(content)
      begin
        Compass.add_project_configuration
        Compass.configuration.project_path ||= Dir.pwd

        load_paths = [".", "./scss", "./css"]
        load_paths += Compass.configuration.sass_load_paths

        engine = Sass::Engine.new(content, :syntax => :scss, :load_paths => load_paths, :style => :compact)
        engine.render
      rescue StandardError => e
        puts "!!! SASS Error: " + e.message
      end
    end
  end
end
